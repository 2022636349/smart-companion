package com.education.agent.feedback.entity;
import com.baomidou.mybatisplus.annotation.*;
import com.education.agent.feedback.model.FeedbackStatus;
import java.time.LocalDateTime;
@TableName("edu_user_feedback")
public class FeedbackEntity {
 @TableId(type=IdType.AUTO) private Long id; private Long userId; private String category; private String title;
 private String content; private String attachmentFileIdsJson; private String pagePath; private FeedbackStatus status;
 private String adminReply; private Long handledBy; private LocalDateTime handledAt; private LocalDateTime createdAt; private LocalDateTime updatedAt;
 public Long getId(){return id;} public void setId(Long v){id=v;} public Long getUserId(){return userId;} public void setUserId(Long v){userId=v;}
 public String getCategory(){return category;} public void setCategory(String v){category=v;} public String getTitle(){return title;} public void setTitle(String v){title=v;}
 public String getContent(){return content;} public void setContent(String v){content=v;} public String getAttachmentFileIdsJson(){return attachmentFileIdsJson;} public void setAttachmentFileIdsJson(String v){attachmentFileIdsJson=v;}
 public String getPagePath(){return pagePath;} public void setPagePath(String v){pagePath=v;} public FeedbackStatus getStatus(){return status;} public void setStatus(FeedbackStatus v){status=v;}
 public String getAdminReply(){return adminReply;} public void setAdminReply(String v){adminReply=v;} public Long getHandledBy(){return handledBy;} public void setHandledBy(Long v){handledBy=v;}
 public LocalDateTime getHandledAt(){return handledAt;} public void setHandledAt(LocalDateTime v){handledAt=v;} public LocalDateTime getCreatedAt(){return createdAt;} public LocalDateTime getUpdatedAt(){return updatedAt;}
}
