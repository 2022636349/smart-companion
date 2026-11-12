package com.education.agent.notification.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.education.agent.assessment.entity.AssessmentEntity;
import com.education.agent.assessment.mapper.AssessmentMapper;
import com.education.agent.assessment.model.AssessmentStatus;
import com.education.agent.notification.entity.NotificationEntity;
import com.education.agent.notification.mapper.NotificationMapper;
import com.education.agent.notification.model.NotificationType;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import com.education.agent.maintenance.MaintenanceLeaseService;
import java.time.Duration;

@Component
public class BusinessNotificationDispatcher {
    private final NotificationMapper notifications;
    private final AssessmentMapper assessments;
    private final MaintenanceLeaseService leases;
    private final String owner="notification-due:"+UUID.randomUUID().toString().substring(0,8);
    public BusinessNotificationDispatcher(NotificationMapper notifications,AssessmentMapper assessments,MaintenanceLeaseService leases){
        this.notifications=notifications;this.assessments=assessments;this.leases=leases;
    }

    @Transactional
    public void onAuditEvent(String action,String targetType,String targetId){
        if(targetId==null)return;
        Long id;try{id=Long.valueOf(targetId);}catch(NumberFormatException ignored){return;}
        switch(action){
            case "ASSESSMENT_PUBLISHED" -> assessmentPublished(id);
            case "ASSESSMENT_RESULTS_RELEASED","ASSESSMENT_RESULTS_AUTO_RELEASED" -> resultsReleased(id);
            case "SUBMISSION_COMMENT_UPDATED" -> teacherComment(id);
            default -> { }
        }
    }

    @Scheduled(fixedDelayString="${education.notification.due-scan-ms:60000}")
    public void scanUpcomingDue(){
        LocalDateTime now=LocalDateTime.now();
        java.time.Instant instant=java.time.Instant.now();
        if(!leases.tryAcquire("notification-due-scan",owner,instant,instant.plus(Duration.ofMinutes(2))))return;
        try{
        List<AssessmentEntity> rows=assessments.selectList(new LambdaQueryWrapper<AssessmentEntity>()
                .eq(AssessmentEntity::getStatus, AssessmentStatus.PUBLISHED)
                .gt(AssessmentEntity::getDueAt,now).le(AssessmentEntity::getDueAt,now.plusDays(1))
                .orderByAsc(AssessmentEntity::getDueAt).last("LIMIT 100"));
        rows.forEach(this::dueSoon);
        }finally{leases.release("notification-due-scan",owner);}
    }

    private void assessmentPublished(Long assessmentId){
        AssessmentEntity assessment=assessments.selectById(assessmentId);if(assessment==null)return;
        String group=group();
        for(Long userId:notifications.classStudentIds(assessment.getClassId())){
            insert(userId,NotificationType.ASSESSMENT_PUBLISHED,"新测评已发布",assessment.getTitle(),"ASSESSMENT",assessmentId,
                    "/assessments/"+assessmentId,group,"ASSESSMENT_PUBLISHED:"+assessmentId);
        }
    }
    private void dueSoon(AssessmentEntity assessment){
        String group=group();
        for(Long userId:notifications.classStudentIds(assessment.getClassId())){
            insert(userId,NotificationType.ASSESSMENT_DUE,"测评即将截止",assessment.getTitle(),"ASSESSMENT",assessment.getId(),
                    "/assessments/"+assessment.getId(),group,"ASSESSMENT_DUE:"+assessment.getId());
        }
    }
    private void resultsReleased(Long assessmentId){
        AssessmentEntity assessment=assessments.selectById(assessmentId);if(assessment==null)return;
        String group=group();
        for(Long userId:notifications.assessmentSubmitterIds(assessmentId)){
            insert(userId,NotificationType.RESULT_RELEASED,"测评结果已公布",assessment.getTitle(),"ASSESSMENT",assessmentId,
                    "/assessments/"+assessmentId+"/result",group,"RESULT_RELEASED:"+assessmentId);
        }
    }
    private void teacherComment(Long submissionId){
        Long userId=notifications.submissionStudentId(submissionId);if(userId==null)return;
        insert(userId,NotificationType.TEACHER_COMMENT,"教师已更新评语","请在结果公布后查看教师评语","COMMENT",submissionId,
                "/submissions/"+submissionId+"/result",group(),"TEACHER_COMMENT:"+submissionId);
    }
    private void insert(Long userId,NotificationType type,String title,String content,String relatedType,Long relatedId,
                        String path,String group,String dedupe){
        if(notifications.selectCount(new LambdaQueryWrapper<NotificationEntity>()
                .eq(NotificationEntity::getUserId,userId).eq(NotificationEntity::getDedupeKey,dedupe))>0)return;
        NotificationEntity row=new NotificationEntity();row.setUserId(userId);row.setNotificationType(type);row.setTitle(title);
        row.setContent(content);row.setRelatedType(relatedType);row.setRelatedId(relatedId);row.setActionPath(path);
        row.setIsRead(false);row.setNotificationGroupId(group);row.setDedupeKey(dedupe);
        try{notifications.insert(row);}catch(DuplicateKeyException ignored){}
    }
    private static String group(){return UUID.randomUUID().toString().replace("-","");}
}
