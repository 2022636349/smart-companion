package com.education.agent.feedback.service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.education.agent.admin.service.AuditLogService;
import com.education.agent.common.api.PageData;
import com.education.agent.common.error.*;
import com.education.agent.feedback.dto.FeedbackDtos.*;
import com.education.agent.feedback.entity.FeedbackEntity;
import com.education.agent.feedback.mapper.FeedbackMapper;
import com.education.agent.feedback.model.FeedbackStatus;
import com.education.agent.file.model.FilePurpose;
import com.education.agent.file.service.FileStorageService;
import com.education.agent.notification.service.NotificationService;
import com.education.agent.security.AuthenticatedUser;
import com.education.agent.user.entity.UserEntity;
import com.education.agent.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service
public class FeedbackService {
 private static final ZoneId DB_ZONE=ZoneId.of("Asia/Shanghai");
 private final FeedbackMapper feedback;private final FileStorageService files;private final UserMapper users;
 private final AuditLogService audit;private final ObjectMapper json;
 public FeedbackService(FeedbackMapper feedback,FileStorageService files,UserMapper users,AuditLogService audit,ObjectMapper json){
  this.feedback=feedback;this.files=files;this.users=users;this.audit=audit;this.json=json;}
 @Transactional
 public FeedbackView create(AuthenticatedUser principal,FeedbackCreateRequest request){
  List<Long> ids=request.attachmentFileIds()==null?List.of():List.copyOf(request.attachmentFileIds());
  if(new HashSet<>(ids).size()!=ids.size())throw validation("attachmentFileIds 不能重复");
  ids.forEach(id->files.requireOwnedAvailable(id,principal, FilePurpose.FEEDBACK_ATTACHMENT));
  if(request.pagePath()!=null)NotificationService.validateActionPath(request.pagePath());
  FeedbackEntity row=new FeedbackEntity();row.setUserId(principal.id());row.setCategory(request.category().name());
  row.setTitle(request.title().trim());row.setContent(request.content().trim());row.setPagePath(trim(request.pagePath()));
  row.setStatus(FeedbackStatus.OPEN);
  try{row.setAttachmentFileIdsJson(json.writeValueAsString(ids));}catch(Exception e){throw new IllegalStateException(e);}
  feedback.insert(row);for(int i=0;i<ids.size();i++)feedback.insertAttachment(row.getId(),ids.get(i),i+1);
  audit.recordRequired("FEEDBACK_CREATED","FEEDBACK",row.getId().toString(),principal.id(),true,Map.of("category",request.category().name(),"count",ids.size()));
  return view(feedback.selectById(row.getId()));
 }
 public PageData<FeedbackView> list(long page,long size,FeedbackStatus status){
  Page<FeedbackEntity> result=feedback.selectPage(Page.of(page,size),new LambdaQueryWrapper<FeedbackEntity>()
    .eq(status!=null,FeedbackEntity::getStatus,status).orderByDesc(FeedbackEntity::getCreatedAt).orderByDesc(FeedbackEntity::getId));
  return new PageData<>(result.getRecords().stream().map(this::view).toList(),result.getCurrent(),result.getSize(),result.getTotal(),result.getPages());
 }
 @Transactional
 public FeedbackView update(AuthenticatedUser principal,Long id,FeedbackUpdateRequest request){
  FeedbackEntity row=feedback.selectForUpdate(id);if(row==null)throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,"反馈不存在");
  row.setStatus(request.status());row.setAdminReply(trim(request.adminReply()));row.setHandledBy(principal.id());row.setHandledAt(LocalDateTime.now());
  feedback.updateById(row);audit.recordRequired("FEEDBACK_UPDATED","FEEDBACK",id.toString(),principal.id(),true,Map.of("feedbackStatus",request.status().name()));
  return view(feedback.selectById(id));
 }
 private FeedbackView view(FeedbackEntity row){
  UserEntity user=users.selectById(row.getUserId());return new FeedbackView(row.getId(),row.getUserId(),user==null?null:user.getUsername(),
    FeedbackCategory.valueOf(row.getCategory()),row.getTitle(),row.getContent(),List.copyOf(feedback.attachmentIds(row.getId())),row.getPagePath(),row.getStatus(),row.getAdminReply(),row.getHandledBy(),
    instant(row.getHandledAt()),instant(row.getCreatedAt()),instant(row.getUpdatedAt()));}
 private static Instant instant(LocalDateTime v){return v==null?null:v.atZone(DB_ZONE).toInstant();}
 private static String trim(String v){return v==null||v.isBlank()?null:v.trim();}
 private static BusinessException validation(String m){return new BusinessException(ErrorCode.VALIDATION_ERROR,m);}
}
