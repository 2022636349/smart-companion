package com.education.agent.graphingestion.integration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Transport objects for the untrusted AI and graph-service boundaries. */
public final class GraphIngestionInternalDtos {
    private GraphIngestionInternalDtos() {}

    public record ExtractSource(
            @JsonProperty("source_type") String sourceType,
            String title,
            @JsonProperty("resource_id") Long resourceId,
            @JsonProperty("file_id") Long fileId,
            @JsonProperty("copyright_status") String copyrightStatus
    ) {}

    public record ExtractReviewRequest(
            @JsonProperty("course_id") Long courseId,
            @JsonProperty("course_name") String courseName,
            @JsonProperty("request_id") String requestId,
            @JsonProperty("job_id") Long jobId,
            @JsonProperty("source_file_id") Long sourceFileId,
            String filename,
            @JsonProperty("download_url") String downloadUrl,
            ExtractSource source,
            @JsonProperty("preferred_language") String preferredLanguage
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ExtractReviewResponse(
            @JsonProperty("course_id") Long courseId,
            @JsonProperty("request_id") String requestId,
            @JsonProperty("job_id") Long jobId,
            List<JsonNode> nodes,
            List<JsonNode> edges,
            List<JsonNode> sources,
            List<String> warnings,
            Models models,
            Prompts prompts
    ) {}

    public record SourceRef(
            @JsonProperty("source_type") String sourceType,
            @JsonProperty("source_id") String sourceId,
            String title,
            @JsonProperty("file_sha256") String fileSha256,
            String locator
    ) {}

    public record ThresholdProfile(
            @JsonProperty("profile_version") String profileVersion,
            @JsonProperty("node_fast_track_min") BigDecimal nodeFastTrackMin,
            @JsonProperty("edge_fast_track_min") BigDecimal edgeFastTrackMin,
            @JsonProperty("source_fast_track_min") BigDecimal sourceFastTrackMin,
            @JsonProperty("reject_below") BigDecimal rejectBelow
    ) {
        public static ThresholdProfile defaults() {
            return new ThresholdProfile("cycle7-v1", new BigDecimal("0.9"),
                    new BigDecimal("0.94"), new BigDecimal("0.95"), new BigDecimal("0.65"));
        }
    }

    public record ModelIdentity(String provider, String model) {}
    public record Models(ModelIdentity extractor, ModelIdentity reviewer) {}
    public record PromptIdentity(String version, String sha256) {}
    public record Prompts(PromptIdentity extractor, PromptIdentity reviewer) {}

    public record BatchUpsertRequest(
            @JsonProperty("request_id") String requestId,
            @JsonProperty("batch_id") String batchId,
            @JsonProperty("base_graph_version") String baseGraphVersion,
            @JsonProperty("payload_sha256") String payloadSha256,
            SourceRef source,
            @JsonProperty("threshold_profile") ThresholdProfile thresholdProfile,
            Models models,
            Prompts prompts,
            List<JsonNode> nodes,
            List<JsonNode> edges,
            List<JsonNode> sources
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ValidationIssue(
            String code,
            String severity,
            @JsonProperty("candidate_type") String candidateType,
            @JsonProperty("candidate_id") String candidateId,
            String message
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record BatchDetail(
            @JsonProperty("course_id") Long courseId,
            @JsonProperty("batch_id") String batchId,
            @JsonProperty("base_graph_version") String baseGraphVersion,
            @JsonProperty("payload_sha256") String payloadSha256,
            String status,
            Long revision,
            @JsonProperty("candidate_count") Integer candidateCount,
            @JsonProperty("pending_count") Integer pendingCount,
            @JsonProperty("approved_count") Integer approvedCount,
            @JsonProperty("rejected_count") Integer rejectedCount,
            @JsonProperty("error_count") Integer errorCount,
            @JsonProperty("warning_count") Integer warningCount,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("updated_at") Instant updatedAt,
            @JsonProperty("published_graph_version") String publishedGraphVersion,
            SourceRef source,
            @JsonProperty("threshold_profile") ThresholdProfile thresholdProfile,
            Models models,
            Prompts prompts,
            List<JsonNode> nodes,
            List<JsonNode> edges,
            List<JsonNode> sources,
            @JsonProperty("validation_issues") List<ValidationIssue> validationIssues
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record BatchUpsertResponse(Boolean duplicate, BatchDetail batch) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CandidateItem(
            @JsonProperty("candidate_type") String candidateType,
            @JsonProperty("candidate_id") String candidateId,
            @JsonProperty("decision_status") String decisionStatus,
            @JsonProperty("review_lane") String reviewLane,
            JsonNode node,
            JsonNode edge,
            JsonNode source
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CandidatePage(List<CandidateItem> items, @JsonProperty("next_cursor") String nextCursor) {}

    public record Decision(
            @JsonProperty("candidate_type") String candidateType,
            @JsonProperty("candidate_id") String candidateId,
            String decision,
            String comment,
            @JsonProperty("edited_node") JsonNode editedNode,
            @JsonProperty("edited_edge") JsonNode editedEdge,
            @JsonProperty("edited_source") JsonNode editedSource
    ) {}

    public record DecisionsRequest(
            @JsonProperty("request_id") String requestId,
            @JsonProperty("expected_revision") Long expectedRevision,
            @JsonProperty("reviewer_id") String reviewerId,
            List<Decision> decisions
    ) {}

    public record PublishRequest(
            @JsonProperty("request_id") String requestId,
            @JsonProperty("expected_revision") Long expectedRevision,
            @JsonProperty("target_graph_version") String targetGraphVersion
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record PublishResponse(
            @JsonProperty("course_id") Long courseId,
            @JsonProperty("batch_id") String batchId,
            @JsonProperty("previous_graph_version") String previousGraphVersion,
            @JsonProperty("graph_version") String graphVersion,
            @JsonProperty("release_sha256") String releaseSha256,
            @JsonProperty("published_at") Instant publishedAt
    ) {}

    public record RollbackRequest(
            @JsonProperty("request_id") String requestId,
            @JsonProperty("expected_current_version") String expectedCurrentVersion
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record RollbackResponse(
            @JsonProperty("course_id") Long courseId,
            @JsonProperty("previous_graph_version") String previousGraphVersion,
            @JsonProperty("graph_version") String graphVersion,
            @JsonProperty("release_sha256") String releaseSha256,
            @JsonProperty("activated_at") Instant activatedAt
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GraphVersion(@JsonProperty("course_id") Long courseId, @JsonProperty("graph_version") String graphVersion) {}
}
