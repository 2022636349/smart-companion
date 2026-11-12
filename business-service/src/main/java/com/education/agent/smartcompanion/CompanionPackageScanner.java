package com.education.agent.smartcompanion;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Bounded ZIP inventory. Paths remain opaque identifiers and never become local paths. */
final class CompanionPackageScanner {
  static final int MAX_ENTRIES=256, MAX_ENTRY=50*1024*1024, MAX_TOTAL=256*1024*1024;
  final int maxEntries,maxEntry,maxTotal,maxRatio;
  CompanionPackageScanner(){this(MAX_ENTRIES,MAX_ENTRY,MAX_TOTAL,200);}
  CompanionPackageScanner(int entries,int entry,int total,int ratio){maxEntries=entries;maxEntry=entry;maxTotal=total;maxRatio=ratio;}
  record Entry(String path, byte[] bytes, String role, boolean recommended, String reason, boolean document) {}
  record Scan(List<Entry> entries,byte[] analysis,boolean dataPresent,List<String> warnings) {}
  static final Map<String,Set<String>> HEADERS=Map.of(
      "模拟学生",Set.of("学生编号","模拟班级编号","年级"),
      "测验节点",Set.of("节点编号","模拟测验日期","可用题目编号"),
      "课程要求",Set.of("指标编号","观察指标","主题目标"),
      "题目与材料",Set.of("材料编号","材料类型","题干或内容","指标编号"),
      "作答记录",Set.of("记录编号","学生编号","节点编号","修订版本","评分状态"),
      "字段说明",Set.of("数据表","字段名","类型","含义"));
  Scan scan(byte[] bytes) throws Exception {
    var entries=new ArrayList<Entry>(); var paths=new HashSet<String>();
    var tables=new LinkedHashMap<String,List<Map<String,String>>>();
    var warnings=new ArrayList<String>(); boolean dataPresent=false, conflict=false; int total=0,count=0;
    try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes),StandardCharsets.UTF_8)) {
      ZipEntry entry;
      while((entry=zip.getNextEntry())!=null) {
        if(++count>maxEntries) throw new IOException("压缩包条目超过限额");
        String path=entry.getName().replace('\\','/'); validatePath(path);
        if(!paths.add(path.toLowerCase(Locale.ROOT))) throw new IOException("压缩包路径重复");
        if(entry.getMethod()!=ZipEntry.STORED&&entry.getMethod()!=ZipEntry.DEFLATED) throw new IOException("不支持的压缩方式");
        if(entry.isDirectory()) {zip.closeEntry();continue;}
        byte[] content=SmartCompanionReader.bounded(zip,maxEntry); zip.closeEntry(); total+=content.length;
        if(total>maxTotal) throw new IOException("累计解压超过限额");
        if(content.length>1024*1024&&(entry.getCompressedSize()<=0||(double)content.length/entry.getCompressedSize()>maxRatio))
          throw new IOException("压缩比超过200，拒绝解压");
        String name=path.substring(path.lastIndexOf('/')+1), ext=extension(name);
        if(name.length()>255) throw new IOException("文件名超过255字符");
        boolean doc=Set.of("txt","md","pdf","docx").contains(ext);
        String text=(ext.equals("txt")||ext.equals("md"))?new String(content,0,Math.min(content.length,12000),StandardCharsets.UTF_8):"";
        String label=name.toLowerCase(Locale.ROOT); String role="UNKNOWN";
        if(label.matches("(?s).*(课程标准|课标|curriculum|standard).*")) role="STANDARD";
        else if(label.matches("(?s).*(教材|教科书|课本|章节|资料切片|textbook|chapter|lesson).*")) role="TEXTBOOK";
        else if(label.matches("(?s).*(竞赛|赛题|比赛|参赛|规则|通知|须知|说明|readme|instructions|competition).*")) role="INSTRUCTIONS";
        else if(text.matches("(?s).*第[一二三四五六七八九十0-9]+章.*")) role="TEXTBOOK";
        boolean recommended=doc&&(role.equals("TEXTBOOK")||role.equals("STANDARD"));
        String reason=doc?(role.equals("UNKNOWN")?"仅识别文本格式，内容角色需确认":role.equals("INSTRUCTIONS")?"说明或规则，默认不进入知识图谱":"文件名或可读正文提示角色，请确认"):
            Set.of("zip","7z","rar").contains(ext)?"嵌套压缩包不展开":"非当前可建图文本，保留在原包清单";
        if(ext.equals("pdf")) reason+="；扫描件无OCR，单批候选有限，请按片段审核";
        entries.add(new Entry(path,content,role,recommended,reason,doc));
        if(Set.of("csv","xlsx","xls","tsv","json").contains(ext)) {
          dataPresent=true;
          try {
            Collection<List<Map<String,String>>> parsed;
            if(ext.equals("csv")) parsed=List.of(SmartCompanionReader.table(SmartCompanionReader.csv(new String(content,StandardCharsets.UTF_8))));
            else if(ext.equals("xlsx")) parsed=SmartCompanionReader.workbook(content,true).values();
            else {warnings.add("数据文件格式待适配："+path);conflict=true;continue;}
            boolean matched=false;
            for(var rows:parsed) {
              if(rows.isEmpty()) continue;
              String kind=HEADERS.entrySet().stream().filter(h->rows.get(0).keySet().containsAll(h.getValue())).map(Map.Entry::getKey).findFirst().orElse(null);
              if(kind==null) continue;
              matched=true; var old=tables.putIfAbsent(kind,rows);
              if(old!=null&&!old.equals(rows)) {conflict=true;warnings.add("同类学情表内容冲突："+kind);}
            }
            if(!matched) {warnings.add("未识别六表学情结构："+path);conflict=true;}
          } catch(Exception e) {warnings.add("学情文件无法可靠解析，需适配："+path);conflict=true;}
        }
      }
    }
    if(entries.isEmpty()) throw new IOException("压缩包为空或无有效文件");
    byte[] analysis=!conflict&&tables.keySet().containsAll(SmartCompanionReader.TABLES)?canonical(tables):null;
    if(dataPresent&&analysis==null) warnings.add("学情结构未完整识别或存在歧义，标记待适配；教材建图仍可继续。");
    warnings.add("自动抽取仅生成候选，必须在系统内人工审核后发布；角色建议不代表内容已核验。");
    if(warnings.size()>100) {int omitted=warnings.size()-99;warnings=new ArrayList<>(warnings.subList(0,99));warnings.add("另有"+omitted+"条警告未展开，请逐项查看文件清单；警告已按100条预算聚合。");}
    return new Scan(entries,analysis,dataPresent,warnings);
  }
  static String extension(String name) {int dot=name.lastIndexOf('.');return dot<0?"":name.substring(dot+1).toLowerCase(Locale.ROOT);}
  static void validatePath(String p) throws IOException {
    if(p.isBlank()||p.length()>1000||p.startsWith("/")||p.contains(":" )||p.chars().anyMatch(c->c<32)) throw new IOException("压缩包路径非法");
    for(String segment:p.split("/",-1)) if(segment.equals(".")||segment.equals("..")) throw new IOException("压缩包路径越界");
  }
  static byte[] canonical(Map<String,List<Map<String,String>>> tables) throws IOException {
    var bytes=new ByteArrayOutputStream();
    try(var zip=new ZipOutputStream(bytes,StandardCharsets.UTF_8)) {
      for(String name:SmartCompanionReader.TABLES) {
        var rows=tables.get(name); var headers=new ArrayList<>(rows.get(0).keySet()); var csv=new StringBuilder();
        appendCsv(csv,headers); for(var row:rows) appendCsv(csv,headers.stream().map(h->row.getOrDefault(h,"")).toList());
        zip.putNextEntry(new ZipEntry(name+".csv"));zip.write(csv.toString().getBytes(StandardCharsets.UTF_8));zip.closeEntry();
      }
    }
    return bytes.toByteArray();
  }
  static void appendCsv(StringBuilder out,List<String> values) {
    for(int i=0;i<values.size();i++){if(i>0)out.append(',');out.append('"').append(values.get(i).replace("\"","\"\"")).append('"');} out.append('\n');
  }
}
