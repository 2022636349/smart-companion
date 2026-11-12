package com.education.agent.notification.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.education.agent.notification.model.NotificationType;
import java.time.LocalDateTime;

@TableName("edu_notification")
public class NotificationEntity {
    @TableId(type = IdType.AUTO) private Long id;
    private Long userId;
    private NotificationType notificationType;
    private String title;
    private String content;
    private String relatedType;
    private Long relatedId;
    private String actionPath;
    private Boolean isRead;
    private LocalDateTime readAt;
    private String notificationGroupId;
    private String dedupeKey;
    private LocalDateTime createdAt;
    public Long getId(){return id;} public void setId(Long v){id=v;}
    public Long getUserId(){return userId;} public void setUserId(Long v){userId=v;}
    public NotificationType getNotificationType(){return notificationType;} public void setNotificationType(NotificationType v){notificationType=v;}
    public String getTitle(){return title;} public void setTitle(String v){title=v;}
    public String getContent(){return content;} public void setContent(String v){content=v;}
    public String getRelatedType(){return relatedType;} public void setRelatedType(String v){relatedType=v;}
    public Long getRelatedId(){return relatedId;} public void setRelatedId(Long v){relatedId=v;}
    public String getActionPath(){return actionPath;} public void setActionPath(String v){actionPath=v;}
    public Boolean getIsRead(){return isRead;} public void setIsRead(Boolean v){isRead=v;}
    public LocalDateTime getReadAt(){return readAt;} public void setReadAt(LocalDateTime v){readAt=v;}
    public String getNotificationGroupId(){return notificationGroupId;} public void setNotificationGroupId(String v){notificationGroupId=v;}
    public String getDedupeKey(){return dedupeKey;} public void setDedupeKey(String v){dedupeKey=v;}
    public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
}
