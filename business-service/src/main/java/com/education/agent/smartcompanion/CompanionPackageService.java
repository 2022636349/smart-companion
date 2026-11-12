package com.education.agent.smartcompanion;

import com.education.agent.common.error.*;
import com.education.agent.file.service.FileStorageService;
import com.education.agent.file.model.FilePurpose;
import com.education.agent.graphingestion.dto.GraphIngestionDtos;
import com.education.agent.graphingestion.service.GraphIngestionService;
import com.education.agent.security.AuthenticatedUser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;

@Service
public class CompanionPackageService {
  final SmartCompanionService imports;
  final FileStorageService files;
  final GraphIngestionService graph;
  final JdbcTemplate jdbc;
  final ObjectMapper json;
  final TransactionTemplate tx;
  final Path root;
  public CompanionPackageService(SmartCompanionService imports,FileStorageService files,GraphIngestionService graph,
      JdbcTemplate jdbc,ObjectMapper json,PlatformTransactionManager manager,
      @Value("${education.file-storage.root:D:/CodexEnvs/EducationAgent/storage}") String root) {
    this.imports=imports;this.files=files;this.graph=graph;this.jdbc=jdbc;this.json=json;
    this.tx=new TransactionTemplate(manager);this.root=Path.of(root).toAbsolutePath().normalize().resolve("companion-packages");
  }
  public record Start(String sourceLabel,boolean acknowledgeWarnings,String copyrightStatus,List<String> documentPaths) {}
  ObjectNode obj(Object...v){return new SmartCompanionEngine(json).obj(v);}
  void require(boolean valid,ErrorCode code,String message){if(!valid)throw new BusinessException(code,message);}
  Map<String,Object> row(Long c,Long id,boolean lock) {
    var rows=jdbc.queryForList("SELECT * FROM edu_companion_package WHERE course_id=? AND id=?"+(lock?" FOR UPDATE":""),c,id);
    require(!rows.isEmpty(),ErrorCode.RESOURCE_NOT_FOUND,"当前课程内无此资源包");return rows.get(0);
  }
  ObjectNode detail(Map<String,Object> row) {return imports.decode(row.get("detail_json"));}
  void save(Long id,ObjectNode detail){jdbc.update("UPDATE edu_companion_package SET detail_json=? WHERE id=?",imports.encode(detail),id);}
  public ObjectNode upload(AuthenticatedUser user,Long course,MultipartFile file) {
    imports.authorize(user,course,true);
    require(file!=null&&!file.isEmpty(),ErrorCode.FILE_EMPTY,"资源包为空");
    require(file.getSize()<=100L*1024*1024,ErrorCode.FILE_TOO_LARGE,"ZIP资源包不得超过100MiB");
    String name=Optional.ofNullable(file.getOriginalFilename()).orElse("").replace('\\','/');name=name.substring(name.lastIndexOf('/')+1);
    require(name.length()<=255&&name.toLowerCase(Locale.ROOT).endsWith(".zip"),ErrorCode.UNSUPPORTED_FILE_TYPE,"请选择ZIP学习资源包");
    byte[] bytes;String hash;
    try{bytes=file.getBytes();hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    catch(Exception e){throw new BusinessException(ErrorCode.FILE_STORAGE_ERROR,"无法读取资源包");}
    var existing=jdbc.queryForList("SELECT id FROM edu_companion_package WHERE course_id=? AND sha256=?",course,hash);
    if(!existing.isEmpty()) return get(user,course,((Number)existing.get(0).get("id")).longValue());
    CompanionPackageScanner.Scan scan;
    try{scan=new CompanionPackageScanner().scan(bytes);}
    catch(Exception e){throw new BusinessException(ErrorCode.VALIDATION_ERROR,"资源包扫描失败："+(e instanceof java.io.IOException?e.getMessage():"不支持加密、损坏或非法ZIP"));}
    final String fileName=name;
    Long id=tx.execute(status->{
      jdbc.queryForList("SELECT id FROM edu_course WHERE id=? FOR UPDATE",course);imports.authorize(user,course,true);
      var duplicate=jdbc.queryForList("SELECT id FROM edu_companion_package WHERE course_id=? AND sha256=?",course,hash);
      if(!duplicate.isEmpty())return ((Number)duplicate.get(0).get("id")).longValue();
      String key=UUID.randomUUID().toString(); Path archive=root.resolve(key+".zip");
      require(root.toString().matches("(?i)^D:.*"),ErrorCode.FILE_STORAGE_ERROR,"资源包存储根须配置在D盘");
      try{Files.createDirectories(root);Files.write(archive,bytes);}
      catch(Exception e){throw new BusinessException(ErrorCode.FILE_STORAGE_ERROR,"资源包保存失败");}
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){public void afterCompletion(int result){if(result!=STATUS_COMMITTED)try{Files.deleteIfExists(archive);}catch(Exception ignored){}}});
      String analysisStatus=scan.dataPresent()?"NEEDS_MAPPING":"NOT_PRESENT", analysisMessage=scan.dataPresent()?"尚未完整识别现有六表学情合同，需要适配；教材可独立建图":"未发现学情表格；可独立处理教材";
      String importId=null;
      if(scan.analysis()!=null) {
        var imported=imports.upload(user,course,new CompanionMultipartFile("recognized-learning-data.zip",scan.analysis()));
        importId=imported.path("summary").path("id").asText();
        boolean ready=imported.path("preview").path("canConfirm").asBoolean();
        analysisStatus=ready?"READY":"FAILED";analysisMessage=ready?"已按真实表头识别六表结构，预检可确认":"六表已识别但预检存在结构问题，请查看导入详情";
      }
      var documents=json.createArrayNode(); var fileIds=new ArrayList<Long>();
      for(var entry:scan.entries()) {
        String base=entry.path().substring(entry.path().lastIndexOf('/')+1);Long fileId=null;String failure=null;
        if(entry.document()) fileId=files.store(user,new CompanionMultipartFile(base,entry.bytes()),FilePurpose.KNOWLEDGE_SOURCE).id();
        fileIds.add(fileId);
        documents.add(obj("path",entry.path(),"fileName",base,"sizeBytes",entry.bytes().length,"role",entry.role(),"recommended",entry.recommended()&&fileId!=null,
            "reason",entry.reason(),"jobId",null,"status",fileId!=null?"DETECTED":"SKIPPED","failureMessage",failure));
      }
      var d=obj("id","0","courseId",course.toString(),"fileName",fileName,"sha256",hash,"status","READY","createdAt",OffsetDateTime.now().toString(),
          "importId",importId,"analysisStatus",analysisStatus,"analysisMessage",analysisMessage,"documents",documents,"warnings",scan.warnings());
      var keyHolder=new GeneratedKeyHolder();final String encoded=imports.encode(d);
      jdbc.update(conn->{var p=conn.prepareStatement("INSERT INTO edu_companion_package(course_id,owner_id,sha256,archive_key,detail_json) VALUES(?,?,?,?,?)",new String[]{"id"});
        p.setLong(1,course);p.setLong(2,user.id());p.setString(3,hash);p.setString(4,key+".zip");p.setString(5,encoded);return p;},keyHolder);
      Long newId=Objects.requireNonNull(keyHolder.getKey()).longValue();d.put("id",newId.toString());save(newId,d);
      for(int i=0;i<documents.size();i++)jdbc.update("INSERT INTO edu_companion_package_document(package_id,entry_no,path,file_id) VALUES(?,?,?,?)",newId,i,documents.get(i).path("path").asText(),fileIds.get(i));
      return newId;
    });
    return get(user,course,id);
  }
  public ObjectNode list(AuthenticatedUser u,Long c) {
    imports.authorize(u,c,false);var items=json.createArrayNode();
    jdbc.queryForList("SELECT id FROM edu_companion_package WHERE course_id=? ORDER BY id DESC LIMIT 100",c)
        .forEach(r->items.add(get(u,c,((Number)r.get("id")).longValue())));return obj("items",items);
  }
  public ObjectNode get(AuthenticatedUser u,Long c,Long id) {
    imports.authorize(u,c,false);var row=row(c,id,false);var d=detail(row);
    boolean pending=false,failed=Set.of("FAILED","NEEDS_MAPPING").contains(d.path("analysisStatus").asText());
    for(var value:d.path("documents")) {
      var doc=(ObjectNode)value;
      if(!doc.path("jobId").isNull())try {
        Long jobId=Long.parseLong(doc.path("jobId").asText());graph.requireJobCourse(c,jobId);var job=graph.get(u,jobId);
        doc.put("status",job.status().name());doc.put("failureMessage",job.failureMessage());
        String state=job.status().name();
        if(Set.of("FAILED","CANCELLED","PARTIALLY_COMPLETED").contains(state))failed=true;
        else if(!Set.of("WAITING_HUMAN_REVIEW","READY_TO_PUBLISH","PUBLISHED").contains(state))pending=true;
      } catch(BusinessException e){doc.put("failureMessage",e.getMessage());failed=true;}
      else if(!doc.path("failureMessage").isNull())failed=true;
    }
    boolean started=((Number)row.get("started")).intValue()!=0;
    d.put("status",pending?"PROCESSING":failed?"NEEDS_ATTENTION":started?"COMPLETED":"READY");return d;
  }
  public ObjectNode start(AuthenticatedUser user,Long c,Long id,Start request) {
    imports.authorize(user,c,true);var snapshot=detail(row(c,id,false));
    require(request!=null&&request.sourceLabel()!=null&&!request.sourceLabel().isBlank()&&request.sourceLabel().length()<=500
        &&request.acknowledgeWarnings()&&Set.of("CONFIRMED","AUTHORIZED","PUBLIC_DOMAIN","UNKNOWN").contains(Objects.requireNonNullElse(request.copyrightStatus(),""))
        &&request.documentPaths()!=null&&request.documentPaths().size()<=256,ErrorCode.VALIDATION_ERROR,"请确认来源、版权与处理清单");
    var chosen=new LinkedHashSet<>(request.documentPaths());
    require(chosen.size()==request.documentPaths().size(),ErrorCode.VALIDATION_ERROR,"文档选择重复");
    var stored=jdbc.queryForList("SELECT * FROM edu_companion_package_document WHERE package_id=? ORDER BY entry_no",id);
    Set<String> selectable=new HashSet<>();for(var r:stored)if(r.get("file_id")!=null)selectable.add(r.get("path").toString());
    require(selectable.containsAll(chosen),ErrorCode.VALIDATION_ERROR,"所选路径不属于可建图文本清单");
    tx.executeWithoutResult(status->{row(c,id,true);imports.authorize(user,c,true);jdbc.update("UPDATE edu_companion_package SET started=1,source_label=? WHERE id=?",request.sourceLabel(),id);});
    if(snapshot.path("analysisStatus").asText().equals("READY")) {
      try {
        Long importId=Long.parseLong(snapshot.path("importId").asText());var imported=imports.get(user,c,importId);
        String label=request.sourceLabel().substring(0,Math.min(300,request.sourceLabel().length()));
        imports.confirm(user,c,importId,new SmartCompanionService.Confirm(imported.path("summary").path("sha256").asText(),label,true));
        imports.analyze(user,c,importId); updateAnalysis(c,id,"ANALYZED","已确认来源并完成确定性学情分析；图谱候选仍需人工审核"+(request.sourceLabel().length()>300?"；完整来源说明保存在包记录，导入摘要使用前300字":""));
      } catch(BusinessException e){updateAnalysis(c,id,"FAILED","学情分析未完成："+e.getMessage());}
    }
    for(var entry:stored) {
      String path=entry.get("path").toString();int index=((Number)entry.get("entry_no")).intValue();
      if(!chosen.contains(path)) {
        tx.executeWithoutResult(status->{var d=detail(row(c,id,true));var doc=(ObjectNode)d.path("documents").get(index);
          if(doc.path("jobId").isNull())doc.put("status","SKIPPED");save(id,d);});continue;
      }
      try {
        tx.executeWithoutResult(status->{
          var d=detail(row(c,id,true));imports.authorize(user,c,true);
          var linked=jdbc.queryForMap("SELECT file_id,job_id FROM edu_companion_package_document WHERE package_id=? AND entry_no=?",id,index);
          if(linked.get("job_id")!=null)return;
          var doc=(ObjectNode)d.path("documents").get(index);String title=doc.path("fileName").asText();
          String sourceType=Set.of("TEXTBOOK","STANDARD").contains(doc.path("role").asText())?"TEXTBOOK":"TEACHING_RESOURCE";
          var job=graph.create(user,"package-"+id+"-document-"+index,new GraphIngestionDtos.CreateRequest(c,((Number)linked.get("file_id")).longValue(),title,sourceType,request.copyrightStatus(),null));
          jdbc.update("UPDATE edu_companion_package_document SET job_id=? WHERE package_id=? AND entry_no=?",job.id(),id,index);
          doc.put("jobId",job.id().toString());doc.put("status",job.status().name());doc.putNull("failureMessage");save(id,d);
        });
      } catch(BusinessException e) {
        tx.executeWithoutResult(status->{var d=detail(row(c,id,true));var doc=(ObjectNode)d.path("documents").get(index);
          if(doc.path("jobId").isNull()){doc.put("status","DETECTED");doc.put("failureMessage","任务创建失败："+e.getMessage());save(id,d);}});
      }
    }
    return get(user,c,id);
  }
  void updateAnalysis(Long c,Long id,String state,String message) {tx.executeWithoutResult(status->{var d=detail(row(c,id,true));d.put("analysisStatus",state);d.put("analysisMessage",message);save(id,d);});}
}
