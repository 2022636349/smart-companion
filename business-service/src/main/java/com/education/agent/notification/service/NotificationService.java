package com.education.agent.notification.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.education.agent.admin.service.AuditLogService;
import com.education.agent.common.error.*;
import com.education.agent.notification.dto.NotificationDtos.*;
import com.education.agent.notification.entity.NotificationEntity;
import com.education.agent.notification.mapper.NotificationMapper;
import com.education.agent.notification.model.NotificationType;
import com.education.agent.security.AuthenticatedUser;
import com.education.agent.tutor.entity.IdempotencyRecordEntity;
import com.education.agent.tutor.mapper.IdempotencyRecordMapper;
import com.education.agent.user.entity.UserEntity;
import com.education.agent.user.mapper.UserMapper;
import com.education.agent.user.model.UserStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

@Service
public class NotificationService {
    private static final ZoneId DB_ZONE=ZoneId.of("Asia/Shanghai");
    private final NotificationMapper notifications;
    private final UserMapper users;
    private final IdempotencyRecordMapper idempotency;
    private final ObjectMapper json;
    private final AuditLogService audit;
    public NotificationService(NotificationMapper notifications, UserMapper users,
            IdempotencyRecordMapper idempotency, ObjectMapper json, AuditLogService audit) {
        this.notifications=notifications; this.users=users; this.idempotency=idempotency; this.json=json; this.audit=audit;
    }

    public NotificationPage list(AuthenticatedUser principal,long page,long size,boolean unreadOnly,NotificationType type) {
        LambdaQueryWrapper<NotificationEntity> query=new LambdaQueryWrapper<NotificationEntity>()
                .eq(NotificationEntity::getUserId,principal.id())
                .eq(unreadOnly,NotificationEntity::getIsRead,false)
                .eq(type!=null,NotificationEntity::getNotificationType,type)
                .orderByDesc(NotificationEntity::getCreatedAt).orderByDesc(NotificationEntity::getId);
        Page<NotificationEntity> result=notifications.selectPage(Page.of(page,size),query);
        long unread=notifications.selectCount(new LambdaQueryWrapper<NotificationEntity>()
                .eq(NotificationEntity::getUserId,principal.id()).eq(NotificationEntity::getIsRead,false));
        return new NotificationPage(result.getCurrent(),result.getSize(),result.getTotal(),result.getPages(),unread,
                result.getRecords().stream().map(this::view).toList());
    }

    @Transactional
    public void markRead(AuthenticatedUser principal,Long id) {
        NotificationEntity row=notifications.selectOne(new LambdaQueryWrapper<NotificationEntity>()
                .eq(NotificationEntity::getId,id).eq(NotificationEntity::getUserId,principal.id()));
        if(row==null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,"通知不存在");
        notifications.markRead(id,principal.id());
    }
    @Transactional public void markAllRead(AuthenticatedUser principal){notifications.markAllRead(principal.id());}

    @Transactional
    public NotificationPublishResult publish(AuthenticatedUser principal,String key,NotificationPublishRequest request) {
        validateActionPath(request.actionPath());
        List<String> roles=request.recipientRoles().stream().map(Enum::name).distinct().sorted().toList();
        if(roles.size()!=request.recipientRoles().size()) throw validation("recipientRoles 不能重复");
        String hash=sha256(request.title().trim()+"\n"+request.content().trim()+"\n"+roles+"\n"+
                (request.type()==null?NotificationType.SYSTEM:request.type())+"\n"+Objects.toString(request.actionPath(),""));
        IdempotencyRecordEntity existing=idempotency.selectOne(new LambdaQueryWrapper<IdempotencyRecordEntity>()
                .eq(IdempotencyRecordEntity::getUserId,principal.id()).eq(IdempotencyRecordEntity::getOperationId,"ADMIN_NOTIFICATION_PUBLISH")
                .eq(IdempotencyRecordEntity::getIdempotencyKey,key));
        if(existing!=null) return replay(existing,hash);
        IdempotencyRecordEntity claim=new IdempotencyRecordEntity(); claim.setUserId(principal.id());
        claim.setOperationId("ADMIN_NOTIFICATION_PUBLISH"); claim.setIdempotencyKey(key); claim.setRequestHash(hash);
        claim.setExpiresAt(LocalDateTime.now().plusHours(24));
        try{idempotency.insert(claim);}catch(DuplicateKeyException race){
            return replay(Objects.requireNonNull(idempotency.selectOne(new LambdaQueryWrapper<IdempotencyRecordEntity>()
                    .eq(IdempotencyRecordEntity::getUserId,principal.id()).eq(IdempotencyRecordEntity::getOperationId,"ADMIN_NOTIFICATION_PUBLISH")
                    .eq(IdempotencyRecordEntity::getIdempotencyKey,key).last("FOR UPDATE"))),hash);
        }
        List<UserEntity> recipients=users.selectList(new LambdaQueryWrapper<UserEntity>()
                .in(UserEntity::getRole,request.recipientRoles()).eq(UserEntity::getStatus, UserStatus.ACTIVE).orderByAsc(UserEntity::getId));
        String group=UUID.randomUUID().toString().replace("-",""); LocalDateTime now=LocalDateTime.now();
        for(UserEntity recipient:recipients) insert(recipient.getId(),request.type()==null?NotificationType.SYSTEM:request.type(),
                request.title().trim(),request.content().trim(),null,null,request.actionPath(),group,"ADMIN:"+group+":"+recipient.getId());
        NotificationPublishResult result=new NotificationPublishResult(group,recipients.size(),now.atZone(DB_ZONE).toInstant());
        try{claim.setResponseStatus(201);claim.setResponseJson(json.writeValueAsString(result));idempotency.updateById(claim);}
        catch(Exception exception){throw new IllegalStateException("通知幂等响应保存失败",exception);}
        audit.recordRequired("ADMIN_NOTIFICATION_PUBLISHED","NOTIFICATION_GROUP",group,principal.id(),true,
                Map.of("createdCount",recipients.size(),"recipientRoles",roles));
        return result;
    }

    public void insert(Long userId,NotificationType type,String title,String content,String relatedType,Long relatedId,
                       String actionPath,String group,String dedupeKey) {
        validateActionPath(actionPath);
        NotificationEntity row=new NotificationEntity(); row.setUserId(userId);row.setNotificationType(type);
        row.setTitle(title);row.setContent(content);row.setRelatedType(relatedType);row.setRelatedId(relatedId);
        row.setActionPath(actionPath);row.setIsRead(false);row.setNotificationGroupId(group);row.setDedupeKey(dedupeKey);
        try{notifications.insert(row);}catch(DuplicateKeyException ignored){}
    }

    private NotificationPublishResult replay(IdempotencyRecordEntity row,String hash) {
        if(!hash.equals(row.getRequestHash())) throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT,"同一幂等键不能用于不同通知内容");
        if(row.getResponseJson()==null) throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION,"相同通知发布请求正在处理中");
        try{return json.readValue(row.getResponseJson(),NotificationPublishResult.class);}
        catch(Exception exception){throw new IllegalStateException("通知幂等响应损坏",exception);}
    }
    public static void validateActionPath(String path) {
        if(path==null)return;
        if(path.isBlank()||!path.startsWith("/")||path.startsWith("//")||path.indexOf('\\')>=0||path.length()>500
                ||path.chars().anyMatch(c->Character.isISOControl(c))) throw validation("actionPath 必须是安全的站内相对路由");
    }
    private NotificationView view(NotificationEntity row){return new NotificationView(row.getId(),row.getNotificationType(),row.getTitle(),row.getContent(),
            row.getRelatedType(),row.getRelatedId(),row.getActionPath(),Boolean.TRUE.equals(row.getIsRead()),instant(row.getReadAt()),instant(row.getCreatedAt()));}
    private static Instant instant(LocalDateTime value){return value==null?null:value.atZone(DB_ZONE).toInstant();}
    private static BusinessException validation(String message){return new BusinessException(ErrorCode.VALIDATION_ERROR,message);}
    private static String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException(e);}}
}
