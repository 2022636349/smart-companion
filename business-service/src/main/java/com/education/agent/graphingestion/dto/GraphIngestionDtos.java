package com.education.agent.graphingestion.dto;

import com.education.agent.graphingestion.model.GraphIngestionStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class GraphIngestionDtos {
    private GraphIngestionDtos() {}

    public record CreateRequest(
            @NotNull @Min(1) Long courseId,
            @NotNull @Min(1) Long fileId,
            @NotBlank @Size(max = 500) String title,
            @NotBlank @Pattern(regexp = "TEXTBOOK|TEACHING_RESOURCE") String sourceType,
            @NotBlank @Pattern(regexp = "CONFIRMED|AUTHORIZED|PUBLIC_DOMAIN|UNKNOWN") String copyrightStatus,
            @Size(max = 100) String baseGraphVersion
    ) {}

    public record CandidateCounts(
            int nodeCount, int edgeCount, int sourceCount,
            int pendingCount, int approvedCount, int rejectedCount,
            int errorCount, int warningCount
    ) {}

    public record JobView(
            Long id, Long courseId, String batchId, Long sourceFileId, String title, String sourceType,
            String copyrightStatus,
            GraphIngestionStatus status, String baseGraphVersion, Long draftRevision,
            int attempt, int maxAttempts, CandidateCounts counts,
            String failureCode, String failureMessage,
            String publishedGraphVersion, String previousGraphVersion, String releaseSha256,
            Long createdBy, long version, Instant createdAt, Instant startedAt,
            Instant completedAt, Instant updatedAt
    ) {}

    public record ValidationIssue(String code, String severity, String message) {}

    public record CandidateNode(
            String candidateId, String id, String name, Integer level, String nodeType,
            String parentId, String description, List<String> aliases, List<String> keywords,
            String difficulty, Integer importance, Integer estimatedMinutes,
            List<String> learningObjectives, List<String> commonMisconceptions,
            List<String> exampleCode, String rightsStatus, List<String> evidence,
            BigDecimal extractorConfidence, BigDecimal reviewerConfidence,
            String reviewStatus, String decisionStatus, String reviewLane,
            String decisionComment, String decidedBy, Instant decidedAt
    ) {}

    public record CandidateEdge(
            String candidateId, String sourceId, String targetId, String relation,
            List<String> evidence, BigDecimal extractorConfidence, BigDecimal reviewerConfidence,
            String reviewStatus, String decisionStatus, String reviewLane,
            String decisionComment, String decidedBy, Instant decidedAt
    ) {}

    public record CandidateSource(
            String candidateId, String id, String nodeId, String sourceType, String title,
            Long resourceId, Long fileId, String chapter, Integer pageFrom, Integer pageTo,
            String locator, String excerpt,
            @NotBlank @Pattern(regexp = "ALLOWED|RESTRICTED") String rightsStatus,
            BigDecimal extractorConfidence,
            BigDecimal reviewerConfidence, String reviewStatus, String decisionStatus,
            String reviewLane, String decisionComment, String decidedBy, Instant decidedAt
    ) {}

    public record CandidateItem(
            String candidateId, String candidateType, String decisionStatus, String reviewLane,
            CandidateNode node, CandidateEdge edge, CandidateSource source,
            List<ValidationIssue> validationIssues, String humanReviewComment,
            String reviewedBy, Instant reviewedAt, long revision
    ) {}

    public record CandidatePage(
            List<CandidateItem> items, long page, long size, long total,
            long totalPages, long draftRevision
    ) {}

    public record Decision(
            @NotBlank @Pattern(regexp = "NODE|EDGE|SOURCE") String candidateType,
            @NotBlank @Size(max = 128) String candidateId,
            @NotBlank @Pattern(regexp = "APPROVE|REJECT") String decision,
            @NotBlank @Size(max = 2000) String comment,
            @Valid CandidateNode editedNode,
            @Valid CandidateEdge editedEdge,
            @Valid CandidateSource editedSource
    ) {}

    public record DecisionBatchRequest(
            @NotNull @Min(1) Long expectedRevision,
            @NotEmpty @Size(max = 100) List<@Valid Decision> decisions
    ) {}

    public record PublishRequest(
            @NotNull @Min(0) Long expectedJobVersion,
            @NotNull @Min(1) Long expectedDraftRevision,
            @NotBlank @Size(max = 100) String targetGraphVersion
    ) {}

    public record PublishResult(
            Long jobId, String batchId, String previousGraphVersion,
            String graphVersion, String releaseSha256, Instant publishedAt
    ) {}

    public record RollbackRequest(
            @NotBlank @Size(max = 100) String expectedCurrentGraphVersion
    ) {}

    public record RollbackResult(
            String previousGraphVersion, String graphVersion,
            String releaseSha256, Instant rolledBackAt
    ) {}
}
