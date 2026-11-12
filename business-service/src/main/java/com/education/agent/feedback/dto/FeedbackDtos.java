package com.education.agent.feedback.dto;
import com.education.agent.feedback.model.FeedbackStatus;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
public final class FeedbackDtos {
 private FeedbackDtos(){}
 public enum FeedbackCategory { BUG, FEATURE, CONTENT, OTHER }
 public record FeedbackCreateRequest(@NotNull FeedbackCategory category,@NotBlank @Size(max=200) String title,
        @NotBlank @Size(max=5000) String content,@Size(max=5) List<@NotNull Long> attachmentFileIds,@Size(max=500) String pagePath){}
 public record FeedbackUpdateRequest(@NotNull FeedbackStatus status,@Size(max=5000) String adminReply){}
 public record FeedbackView(Long id,Long userId,String userName,FeedbackCategory category,String title,String content,
        List<Long> attachmentFileIds,String pagePath,FeedbackStatus status,String adminReply,Long handledBy,Instant handledAt,Instant createdAt,Instant updatedAt){}
}
