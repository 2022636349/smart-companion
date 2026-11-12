package com.education.agent.tutor.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.education.agent.admin.service.AuditLogService;
import com.education.agent.common.api.PageData;
import com.education.agent.common.error.BusinessException;
import com.education.agent.common.error.ErrorCode;
import com.education.agent.common.web.RequestIds;
import com.education.agent.course.CourseAccessService;
import com.education.agent.integration.ai.AiCognitiveLabel;
import com.education.agent.integration.ai.AiServiceClient;
import com.education.agent.integration.ai.AiTutorChatRequest;
import com.education.agent.integration.ai.AiTutorChatResponse;
import com.education.agent.integration.ai.AiTutorContentBlock;
import com.education.agent.integration.ai.AiTutorHistoryMessage;
import com.education.agent.integration.ai.AiTutorSpecialistInvocation;
import com.education.agent.integration.ai.AiTutorTeachingPlan;
import com.education.agent.integration.knowledge.InternalGraphNode;
import com.education.agent.integration.knowledge.InternalGraphRagContextResponse;
import com.education.agent.integration.knowledge.ApprovedGraphContextValidator;
import com.education.agent.integration.knowledge.KnowledgeServiceClient;
import com.education.agent.learning.memory.MemoryService;
import com.education.agent.learning.memory.ProfileSummaryService;
import com.education.agent.security.AuthenticatedUser;
import com.education.agent.resource.entity.TeachingResourceEntity;
import com.education.agent.resource.mapper.TeachingResourceMapper;
import com.education.agent.resource.model.ReviewStatus;
import com.education.agent.tutor.dto.CognitiveLabelView;
import com.education.agent.tutor.dto.ContentBlockView;
import com.education.agent.tutor.dto.KnowledgePointBinding;
import com.education.agent.tutor.dto.TutorMessageView;
import com.education.agent.tutor.dto.TutorReply;
import com.education.agent.tutor.dto.TutorSessionCreateRequest;
import com.education.agent.tutor.dto.TutorSessionView;
import com.education.agent.tutor.dto.TutorSourceView;
import com.education.agent.tutor.entity.CognitiveLabelEntity;
import com.education.agent.tutor.entity.IdempotencyRecordEntity;
import com.education.agent.tutor.entity.TutorMessageEntity;
import com.education.agent.tutor.entity.TutorSessionEntity;
import com.education.agent.tutor.mapper.CognitiveLabelMapper;
import com.education.agent.tutor.mapper.IdempotencyRecordMapper;
import com.education.agent.tutor.mapper.TutorMessageMapper;
import com.education.agent.tutor.mapper.TutorSessionMapper;
import com.education.agent.tutor.model.ContentBlockType;
import com.education.agent.tutor.model.MasteryStatus;
import com.education.agent.tutor.model.TutorMessageRole;
import com.education.agent.tutor.model.TutorActivityType;
import com.education.agent.tutor.model.TutorSessionStatus;
import com.education.agent.tutor.model.TutorTeachingMode;
import com.education.agent.tutor.service.TutorActivityDraftValidator.PreparedActivities;
import com.education.agent.tutor.service.TutorActivityDraftValidator.PreparedActivity;
import com.education.agent.user.model.UserRole;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Comparator;
import java.util.HashSet;
import java.util.regex.Pattern;

@Service
public class TutorService {

    private static final ZoneId DATABASE_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_HISTORY_MESSAGES = 20;
    private static final int MAX_HISTORY_MESSAGE_CHARS = 20_000;
    private static final int MAX_HISTORY_TOTAL_CHARS = 40_000;
    private static final int MAX_TUTOR_ITEMS = 20;
    private static final int MAX_TUTOR_ANSWER_CHARS = 20_000;
    private static final int MAX_TUTOR_TEXT_CHARS = 2_000;
    private static final int MAX_TUTOR_MAP_JSON_BYTES = 16_384;
    private static final int MAX_TUTOR_PERSISTED_JSON_BYTES = 65_536;
    private static final Pattern BLOCK_ID = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]{0,79}$");
    private static final Pattern SAFE_ERROR_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,79}$");
    private static final Set<String> SPECIALIST_AGENTS =
            Set.of("EXPLANATION", "QUIZ", "CODE_LAB", "ANIMATION");
    private static final Set<String> SPECIALIST_STATUSES =
            Set.of("SUCCEEDED", "FAILED", "TIMED_OUT", "SKIPPED");
    private static final TypeReference<List<ContentBlockView>> BLOCK_LIST = new TypeReference<>() { };
    private static final TypeReference<List<TutorSourceView>> SOURCE_LIST = new TypeReference<>() { };

    private final TutorSessionMapper sessionMapper;
    private final TutorMessageMapper messageMapper;
    private final CognitiveLabelMapper cognitiveLabelMapper;
    private final IdempotencyRecordMapper idempotencyMapper;
    private final KnowledgeServiceClient knowledgeServiceClient;
    private final AiServiceClient aiServiceClient;
    private final AuditLogService auditLogService;
    private final MemoryService memoryService;
    private final ProfileSummaryService profileSummaryService;
    private final TutorRouterContextService routerContextService;
    private final TutorAgentTraceValidator traceValidator;
    private final TutorActivityDraftValidator activityDraftValidator;
    private final TutorActivityService activityService;
    private final TeachingResourceMapper teachingResourceMapper;
    private final CourseAccessService courses;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public TutorService(
            TutorSessionMapper sessionMapper,
            TutorMessageMapper messageMapper,
            CognitiveLabelMapper cognitiveLabelMapper,
            IdempotencyRecordMapper idempotencyMapper,
            KnowledgeServiceClient knowledgeServiceClient,
            AiServiceClient aiServiceClient,
            AuditLogService auditLogService,
            MemoryService memoryService,
            ProfileSummaryService profileSummaryService,
            TutorRouterContextService routerContextService,
            TutorAgentTraceValidator traceValidator,
            TutorActivityDraftValidator activityDraftValidator,
            TutorActivityService activityService,
            TeachingResourceMapper teachingResourceMapper,
            CourseAccessService courses,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager
    ) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.cognitiveLabelMapper = cognitiveLabelMapper;
        this.idempotencyMapper = idempotencyMapper;
        this.knowledgeServiceClient = knowledgeServiceClient;
        this.aiServiceClient = aiServiceClient;
        this.auditLogService = auditLogService;
        this.memoryService = memoryService;
        this.profileSummaryService = profileSummaryService;
        this.routerContextService = routerContextService;
        this.traceValidator = traceValidator;
        this.activityDraftValidator = activityDraftValidator;
        this.activityService = activityService;
        this.teachingResourceMapper = teachingResourceMapper;
        this.courses = courses;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public PageData<TutorSessionView> list(
            AuthenticatedUser principal,
            Long courseId,
            long page,
            long size,
            String knowledgePointId
    ) {
        requireStudent(principal);
        courses.requireView(principal, courseId);
        LambdaQueryWrapper<TutorSessionEntity> query = new LambdaQueryWrapper<TutorSessionEntity>()
                .eq(TutorSessionEntity::getStudentId, principal.id())
                .eq(TutorSessionEntity::getCourseId, courseId)
                .eq(knowledgePointId != null && !knowledgePointId.isBlank(),
                        TutorSessionEntity::getKnowledgePointId, knowledgePointId)
                .orderByDesc(TutorSessionEntity::getUpdatedAt);
        Page<TutorSessionEntity> result = sessionMapper.selectPage(Page.of(page, size), query);
        List<TutorSessionView> items = result.getRecords().stream()
                .map(entity -> toSessionView(entity, false))
                .toList();
        return new PageData<>(items, result.getCurrent(), result.getSize(), result.getTotal(), result.getPages());
    }

    public TutorSessionView get(AuthenticatedUser principal, Long sessionId) {
        requireStudent(principal);
        return toSessionView(requireOwnedSession(principal, sessionId), true);
    }

    public TutorSessionView create(AuthenticatedUser principal, TutorSessionCreateRequest request) {
        requireStudent(principal);
        courses.requireStudy(principal, request.courseId());
        courses.requireQuestionInCourse(principal, request.courseId(), request.sourceQuestionId());
        String knowledgePointId = request.knowledgePointId().trim();
        InternalGraphRagContextResponse graphContext = courseGraphContext(request.courseId(), knowledgePointId);
        String knowledgePointName = graphContext.knowledgePoint().name();
        if (knowledgePointName == null || knowledgePointName.isBlank()) {
            throw new BusinessException(ErrorCode.AI_SERVICE_INVALID_RESPONSE, "图谱返回的知识点名称为空");
        }

        TutorSessionEntity created = transactionTemplate.execute(status -> {
            courses.requireStudy(principal, request.courseId());
            TutorSessionEntity entity = new TutorSessionEntity();
            entity.setStudentId(principal.id());
            entity.setCourseId(request.courseId());
            entity.setGraphVersion(graphContext.graphVersion());
            entity.setKnowledgePointId(knowledgePointId);
            entity.setKnowledgePointName(knowledgePointName);
            entity.setEntrySource(request.entrySource());
            entity.setSourceQuestionId(request.sourceQuestionId());
            entity.setStatus(TutorSessionStatus.ACTIVE);
            sessionMapper.insert(entity);
            return sessionMapper.selectById(entity.getId());
        });
        if (created == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "创建导师会话失败");
        }
        auditLogService.record(
                "TUTOR_SESSION_CREATED", "TUTOR_SESSION", created.getId().toString(),
                principal.id(), true, Map.of("knowledgePointId", knowledgePointId)
        );
        return toSessionView(created, true);
    }

    public TutorReply sendMessage(
            AuthenticatedUser principal,
            Long sessionId,
            String idempotencyKey,
            String message
    ) {
        return sendMessage(
                principal, sessionId, idempotencyKey, message, RequestIds.current(),
                TutorTeachingMode.AUTO);
    }

    public TutorReply sendMessage(
            AuthenticatedUser principal,
            Long sessionId,
            String idempotencyKey,
            String message,
            String requestId
    ) {
        return sendMessage(
                principal, sessionId, idempotencyKey, message, requestId,
                TutorTeachingMode.AUTO);
    }

    public TutorReply sendMessage(
            AuthenticatedUser principal,
            Long sessionId,
            String idempotencyKey,
            String message,
            String requestId,
            TutorTeachingMode teachingMode
    ) {
        requireStudent(principal);
        TutorSessionEntity session = requireOwnedSession(principal, sessionId);
        courses.requireStudy(principal, session.getCourseId());
        if (session.getStatus() != TutorSessionStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.INVALID_RESOURCE_STATE, "导师会话已经结束");
        }

        String normalizedMessage = message.trim();
        List<String> enabledActivities = courses.activities(session.getCourseId());
        TutorTeachingMode normalizedTeachingMode = courseTeachingMode(teachingMode, enabledActivities);
        String operationId = "TUTOR_MESSAGE:" + sessionId;
        String requestHash = sha256(normalizedTeachingMode == TutorTeachingMode.AUTO
                ? sessionId + "\n" + normalizedMessage
                : sessionId + "\n" + normalizedTeachingMode.name() + "\n" + normalizedMessage);
        IdempotencyClaim claim = claim(principal.id(), operationId, idempotencyKey, requestHash);
        if (claim.replay() != null) {
            return claim.replay();
        }

        try {
            List<TutorMessageEntity> history = listMessageEntities(sessionId);
            InternalGraphRagContextResponse graphContext = courseGraphContext(
                    session.getCourseId(), session.getKnowledgePointId());
            Set<String> allowedLabelKnowledgePoints = ApprovedGraphContextValidator.allowedContextNodeIds(
                    session.getKnowledgePointId(), graphContext
            );
            boolean personalizationEnabled = memoryService.personalizationEnabled(principal.id());
            TutorRouterContextService.RouterContext routerContext =
                    routerContextService.forStudent(principal.id(), session.getCourseId(), session.getKnowledgePointId());
            AiTutorChatResponse aiResponse = aiServiceClient.tutorChat(new AiTutorChatRequest(
                    session.getCourseId(),
                    (String) courses.requireCourse(session.getCourseId()).get("name"),
                    enabledActivities,
                    requestId,
                    principal.id(),
                    sessionId,
                    session.getKnowledgePointId(),
                    normalizedMessage,
                    toAiHistory(history),
                    Map.of("entry_source", session.getEntrySource().name(),
                            "personalization_enabled", personalizationEnabled),
                    routerContext.agentMode(),
                    normalizedTeachingMode.name(),
                    routerContext.masteryContext()
            ));
            boolean memoryEpochReconciledBefore = true;
            if (aiResponse != null && aiResponse.memoryIndexEpoch() != null) {
                memoryEpochReconciledBefore = memoryService.handleIndexSignal(
                        aiResponse.memoryIndexEpoch(), aiResponse.memoryRebuildRequired());
            }
            if (aiResponse != null
                    && !memoryEpochReconciledBefore
                    && aiResponse.memoryIdsUsed() != null
                    && !aiResponse.memoryIdsUsed().isEmpty()) {
                throw new BusinessException(
                        ErrorCode.CONCURRENT_MODIFICATION,
                        "记忆索引刚进入恢复流程，请重试本次提问"
                );
            }
            memoryService.validateRecall(principal.id(), session.getCourseId(), aiResponse == null ? null : aiResponse.memoryIdsUsed(),
                    personalizationEnabled);
            ValidatedAiReply validated = validateAiResponse(
                    session, requestId, aiResponse, allowedLabelKnowledgePoints,
                    routerContext.agentMode(), normalizedTeachingMode, graphContext);
            TutorReply reply = persistReply(
                    principal, session, claim.recordId(), normalizedMessage, requestId, validated,
                    aiResponse.memoryIdsUsed()
            );
            auditLogService.record(
                    "TUTOR_AI_CALL", "TUTOR_SESSION", sessionId.toString(),
                    principal.id(), true,
                    Map.of(
                            "modelVersion", aiResponse.modelVersion(),
                            "promptVersion", aiResponse.promptVersion(),
                            "sourceCount", validated.sources().size(),
                            "agent", traceValidator.auditSummary(aiResponse.agentTrace())
                    )
            );
            return reply;
        } catch (RuntimeException exception) {
            releaseClaim(claim.recordId());
            auditLogService.record(
                    "TUTOR_AI_CALL", "TUTOR_SESSION", sessionId.toString(),
                    principal.id(), false, Map.of("errorType", exception.getClass().getSimpleName())
            );
            throw exception;
        }
    }

    public TutorSessionView close(AuthenticatedUser principal, Long sessionId) {
        requireStudent(principal);
        TutorSessionEntity session = requireOwnedSession(principal, sessionId);
        if (session.getStatus() == TutorSessionStatus.CLOSED) {
            profileSummaryService.enqueue(session);
            return toSessionView(session, true);
        }
        TutorSessionEntity closed = transactionTemplate.execute(status -> {
            TutorSessionEntity latest = sessionMapper.selectByIdForUpdate(sessionId);
            if(latest==null||!latest.getStudentId().equals(principal.id()))throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,"导师会话不存在");
            if(latest.getStatus()==TutorSessionStatus.CLOSED){profileSummaryService.enqueue(latest);return latest;}
            latest.setStatus(TutorSessionStatus.CLOSED);
            latest.setSummary("会话已结束，共保存 " + messageCount(sessionId) + " 条消息。");
            latest.setUpdatedAt(LocalDateTime.now());
            sessionMapper.updateById(latest);
            profileSummaryService.enqueue(latest);
            return sessionMapper.selectById(sessionId);
        });
        auditLogService.record(
                "TUTOR_SESSION_CLOSED", "TUTOR_SESSION", sessionId.toString(),
                principal.id(), true, Map.of()
        );
        return toSessionView(Objects.requireNonNull(closed), true);
    }

    public void delete(AuthenticatedUser principal, Long sessionId) {
        requireStudent(principal);
        requireOwnedSession(principal, sessionId);
        transactionTemplate.executeWithoutResult(status -> {
            TutorSessionEntity latest = sessionMapper.selectByIdForUpdate(sessionId);
            if (latest == null || !latest.getStudentId().equals(principal.id())) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "导师会话不存在");
            }
            profileSummaryService.cancelForDeletedSession(sessionId);
            sessionMapper.deleteById(sessionId);
        });
        auditLogService.record(
                "TUTOR_SESSION_DELETED", "TUTOR_SESSION", sessionId.toString(),
                principal.id(), true, Map.of("contentRetainedForAudit", true)
        );
    }

    private TutorReply persistReply(
            AuthenticatedUser principal,
            TutorSessionEntity session,
            Long idempotencyRecordId,
            String userContent,
            String requestId,
            ValidatedAiReply validated,
            List<Long> memoryIdsUsed
    ) {
        Long studentId = principal.id();
        TutorReply result = transactionTemplate.execute(status -> {
            courses.requireStudy(principal, session.getCourseId());
            memoryService.validateRecallAtCommit(studentId, session.getCourseId(), memoryIdsUsed);
            TutorSessionEntity latest = sessionMapper.selectByIdForUpdate(session.getId());
            if (latest == null || !latest.getStudentId().equals(studentId)) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "导师会话不存在");
            }
            if (latest.getStatus() != TutorSessionStatus.ACTIVE) {
                throw new BusinessException(ErrorCode.INVALID_RESOURCE_STATE, "导师会话已经结束");
            }
            requireActivityCapabilities(validated.teachingPlan(), courses.activities(latest.getCourseId()));
            revalidateResourcesAtCommit(validated, latest.getCourseId(), latest.getKnowledgePointId());

            TutorMessageEntity userMessage = new TutorMessageEntity();
            userMessage.setSessionId(session.getId());
            userMessage.setMessageRole(TutorMessageRole.USER);
            userMessage.setContent(userContent);
            userMessage.setContentBlocksJson("[]");
            userMessage.setSourcesJson("[]");
            userMessage.setRequestId(requestId);
            messageMapper.insert(userMessage);

            TutorMessageEntity assistantMessage = new TutorMessageEntity();
            assistantMessage.setSessionId(session.getId());
            assistantMessage.setMessageRole(TutorMessageRole.ASSISTANT);
            assistantMessage.setContent(validated.answer());
            assistantMessage.setContentBlocksJson("[]");
            assistantMessage.setSourcesJson(writeJson(validated.sources()));
            assistantMessage.setRequestId(requestId);
            assistantMessage.setModelVersion(validated.modelVersion());
            assistantMessage.setPromptVersion(validated.promptVersion());
            assistantMessage.setTeachingPlanJson(writeJson(validated.teachingPlan()));
            assistantMessage.setSpecialistTraceJson(writeJson(validated.specialistTrace()));
            messageMapper.insert(assistantMessage);

            Map<String, Long> activityIds = activityService.persistActivities(
                    studentId, session.getId(), assistantMessage.getId(),
                    validated.activities());
            List<ContentBlockView> resolvedBlocks = validated.blocks().stream()
                    .map(block -> block.resolve(activityIds))
                    .toList();
            requirePersistedJsonBound(resolvedBlocks, "内容块");
            assistantMessage.setContentBlocksJson(writeJson(resolvedBlocks));
            messageMapper.updateById(assistantMessage);

            for (CognitiveLabelView label : validated.labels()) {
                CognitiveLabelEntity entity = new CognitiveLabelEntity();
                entity.setStudentId(studentId);
                entity.setCourseId(session.getCourseId());
                entity.setKnowledgePointId(label.knowledgePointId());
                entity.setSourceType("TUTOR");
                entity.setSourceId(assistantMessage.getId());
                entity.setLabelStatus(label.status());
                entity.setConfidence(label.confidence());
                entity.setEvidence(label.evidence());
                cognitiveLabelMapper.insert(entity);
            }

            latest.setUpdatedAt(LocalDateTime.now());
            sessionMapper.updateById(latest);

            TutorReply reply = new TutorReply(
                    toMessageView(requireMessage(userMessage.getId())),
                    toMessageView(requireMessage(assistantMessage.getId())),
                    latest.getKnowledgePointId(),
                    validated.labels(),
                    validated.suggestedNextStep()
            );
            IdempotencyRecordEntity record = idempotencyMapper.selectById(idempotencyRecordId);
            if (record == null || record.getResponseJson() != null) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT, "幂等请求状态已发生变化");
            }
            record.setResponseStatus(200);
            record.setResponseJson(writeJson(reply));
            idempotencyMapper.updateById(record);
            return reply;
        });
        if (result == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "保存导师回复失败");
        }
        return result;
    }

    private ValidatedAiReply validateAiResponse(
            TutorSessionEntity session,
            String expectedRequestId,
            AiTutorChatResponse response,
            Set<String> allowedLabelKnowledgePoints,
            String requestedAgentMode,
            TutorTeachingMode requestedTeachingMode,
            InternalGraphRagContextResponse graphContext
    ) {
        if (response == null
                || !session.getCourseId().equals(response.courseId())
                || !expectedRequestId.equals(response.requestId())
                || !session.getKnowledgePointId().equals(response.knowledgePointId())
                || response.answer() == null
                || response.answer().isBlank()
                || response.answer().length() > MAX_TUTOR_ANSWER_CHARS
                || response.modelVersion() == null
                || response.modelVersion().isBlank()
                || response.modelVersion().length() > 100
                || response.promptVersion() == null
                || response.promptVersion().isBlank()
                || response.promptVersion().length() > 100
                || response.memoryIdsUsed() == null
                || response.memoryRebuildRequired() == null
                || (response.memoryIndexEpoch() == null && response.memoryRebuildRequired())
                || (!response.memoryIdsUsed().isEmpty() && response.memoryIndexEpoch() == null)
                || (response.memoryIndexEpoch() != null && (response.memoryIndexEpoch().length() < 16
                    || response.memoryIndexEpoch().length() > 64))) {
            throw invalidAiResponse("基础响应字段缺失或请求追踪不一致");
        }
        if(response.suggestedNextStep()!=null&&response.suggestedNextStep().length()>MAX_TUTOR_TEXT_CHARS)
            throw invalidAiResponse("下一步建议过长");
        traceValidator.validate(requestedAgentMode, response.agentTrace());

        PreparedActivities preparedActivities = activityDraftValidator.validate(
                response.activities(), approvedContextNames(graphContext), graphContext.graphVersion());
        List<String> enabledActivities = courses.activities(session.getCourseId());
        requireActivityCapabilities(response.teachingPlan(), enabledActivities);
        validateTeachingOrchestration(
                response.teachingPlan(), response.specialistTrace(), preparedActivities.items(),
                requestedTeachingMode, enabledActivities);

        List<TutorSourceView> sources = validateSources(response.sources(), session.getCourseId(), session.getKnowledgePointId());
        List<PreparedContentBlock> blocks = validateBlocks(
                response.blocks(), sources, session.getCourseId(), session.getKnowledgePointId(), preparedActivities.byRef());
        List<CognitiveLabelView> labels = validateLabels(response.cognitiveLabels(),allowedLabelKnowledgePoints);
        requirePersistedJsonBound(
                blocks.stream().map(PreparedContentBlock::block).toList(), "内容块");
        requirePersistedJsonBound(sources,"来源");
        requirePersistedJsonBound(response.teachingPlan(), "教学计划");
        requirePersistedJsonBound(response.specialistTrace(), "专家轨迹");
        return new ValidatedAiReply(
                response.answer().trim(),
                blocks,
                sources,
                labels,
                trimToNull(response.suggestedNextStep()),
                response.modelVersion(),
                response.promptVersion(),
                preparedActivities.items(),
                response.teachingPlan(),
                response.specialistTrace()
        );
    }

    private InternalGraphRagContextResponse courseGraphContext(Long courseId, String knowledgePointId) {
        InternalGraphRagContextResponse context = ApprovedGraphContextValidator.require(
                knowledgePointId, knowledgeServiceClient.ragContext(courseId, knowledgePointId));
        if (!courseId.equals(context.courseId())) {
            throw new BusinessException(ErrorCode.GRAPH_SERVICE_UNAVAILABLE, "图谱响应课程不一致");
        }
        return context;
    }

    private TutorTeachingMode courseTeachingMode(TutorTeachingMode requested, List<String> enabled) {
        TutorTeachingMode mode = requested == null ? TutorTeachingMode.AUTO : requested;
        if ((mode == TutorTeachingMode.CODE && !enabled.contains("CODE"))
                || (mode == TutorTeachingMode.QUIZ && !enabled.contains("QUIZ"))
                || (mode == TutorTeachingMode.VISUAL && !enabled.contains("VISUAL"))
                || (mode == TutorTeachingMode.MIXED && enabled.size() == 1)) {
            return TutorTeachingMode.EXPLAIN;
        }
        return mode;
    }

    private void requireActivityCapabilities(AiTutorTeachingPlan plan, List<String> enabled) {
        if (plan == null || plan.specialistAgents() == null) {
            throw invalidAiResponse("教学计划缺失");
        }
        for (String agent : plan.specialistAgents()) {
            String capability = switch (agent == null ? "" : agent) {
                case "EXPLANATION" -> "EXPLAIN";
                case "QUIZ" -> "QUIZ";
                case "CODE_LAB" -> "CODE";
                case "ANIMATION" -> "VISUAL";
                default -> "";
            };
            if (!enabled.contains(capability)) {
                throw invalidAiResponse("教学计划包含当前课程未启用的能力");
            }
        }
    }

    private List<PreparedContentBlock> validateBlocks(
            List<AiTutorContentBlock> input,
            List<TutorSourceView> sources,
            Long courseId,
            String knowledgePointId,
            Map<String, PreparedActivity> activitiesByRef) {
        if (input == null || input.isEmpty() || input.size()>MAX_TUTOR_ITEMS) {
            throw invalidAiResponse("结构化内容块为空");
        }
        List<PreparedContentBlock> result = new ArrayList<>();
        Set<String> seenBlockIds = new HashSet<>();
        Set<String> seenActivityRefs = new HashSet<>();
        for (AiTutorContentBlock block : input) {
            if (block == null || block.type() == null) {
                throw invalidAiResponse("内容块类型缺失");
            }
            if(tooLong(block.content(),MAX_TUTOR_ANSWER_CHARS)||tooLong(block.title(),MAX_TUTOR_TEXT_CHARS)
                    ||tooLong(block.language(),64)||tooLong(block.activityRef(),80))
                throw invalidAiResponse("内容块文本过长");
            String blockId = trimToNull(block.blockId());
            int schemaVersion = block.schemaVersion() == null ? 1 : block.schemaVersion();
            if ((blockId != null && (!BLOCK_ID.matcher(blockId).matches() || !seenBlockIds.add(blockId)))
                    || schemaVersion < 1 || schemaVersion > 10) {
                throw invalidAiResponse("内容块稳定标识或协议版本不合法");
            }
            requireMapBound(block.metadata(),"内容块元数据");
            ContentBlockType type;
            try {
                type = ContentBlockType.valueOf(block.type().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw invalidAiResponse("内容块类型不受支持");
            }
            Long resourceId = block.resourceId();
            String activityRef = trimToNull(block.activityRef());
            if (activityRef != null) {
                PreparedActivity activity = activitiesByRef.get(activityRef);
                if (activity == null || blockId == null || resourceId != null
                        || !seenActivityRefs.add(activityRef)
                        || !activityBlockType(activity.activityType()).equals(type)) {
                    throw invalidAiResponse("互动内容块与已验证活动不匹配");
                }
                result.add(new PreparedContentBlock(
                        new ContentBlockView(type, blockId, schemaVersion, null,
                                trimToNull(block.content()), null, trimToNull(block.title()),
                                trimToNull(block.language()), sanitizedMetadata(block.metadata())),
                        activityRef));
                continue;
            }
            if (type == ContentBlockType.QUIZ || type == ContentBlockType.CODE_LAB
                    || (type == ContentBlockType.ANIMATION && resourceId == null)) {
                throw invalidAiResponse("互动内容块缺少活动引用");
            }
            boolean resourceType = type == ContentBlockType.DOCUMENT || type == ContentBlockType.ANIMATION
                    || type == ContentBlockType.VIDEO || type == ContentBlockType.IMAGE;
            if (resourceType && resourceId == null) {
                result.add(new PreparedContentBlock(new ContentBlockView(
                        ContentBlockType.MARKDOWN, blockId, schemaVersion, null,
                        block.content(), null, block.title(), null, Map.of()), null));
                continue;
            }
            Map<String, Object> metadata = sanitizedMetadata(block.metadata());
            if (resourceId != null) {
                boolean cited = sources.stream().anyMatch(source -> resourceId.equals(source.resourceId()));
                TeachingResourceEntity resource = cited ? approvedResource(resourceId, courseId, knowledgePointId) : null;
                if (!cited) {
                    throw invalidAiResponse("内容块引用了未审核或未引用的教学资源");
                }
                Map<String, Object> authoritative = new LinkedHashMap<>(metadata);
                authoritative.put("reviewed", true);
                authoritative.put("approved", true);
                if (resource.getFileId() != null) {
                    authoritative.put("url", "/api/files/" + resource.getFileId() + "/content");
                } else if (resource.getExternalUrl() != null && !resource.getExternalUrl().isBlank()) {
                    authoritative.put("url", resource.getExternalUrl());
                }
                metadata = Map.copyOf(authoritative);
            }
            result.add(new PreparedContentBlock(new ContentBlockView(
                    type, blockId, schemaVersion, null, block.content(), resourceId,
                    block.title(), block.language(), metadata), null));
        }
        if (!seenActivityRefs.equals(activitiesByRef.keySet())) {
            throw invalidAiResponse("每个互动活动必须且只能由一个内容块引用");
        }
        return List.copyOf(result);
    }

    private void validateTeachingOrchestration(
            AiTutorTeachingPlan plan,
            List<AiTutorSpecialistInvocation> trace,
            List<PreparedActivity> activities,
            TutorTeachingMode requestedMode,
            List<String> enabledActivities) {
        if (plan == null || plan.goal() == null || plan.goal().isBlank()
                || plan.goal().length() > 500 || plan.modalities() == null
                || plan.specialistAgents() == null
                || !plan.modalities().equals(plan.specialistAgents())
                || plan.specialistAgents().isEmpty() || plan.specialistAgents().size() > 3
                || !"EXPLANATION".equals(plan.specialistAgents().get(0))
                || !SPECIALIST_AGENTS.containsAll(plan.specialistAgents())
                || new HashSet<>(plan.specialistAgents()).size() != plan.specialistAgents().size()) {
            throw invalidAiResponse("教学计划结构不合法");
        }
        List<String> expectedAgents = switch (requestedMode) {
            case EXPLAIN -> List.of("EXPLANATION");
            case QUIZ -> List.of("EXPLANATION", "QUIZ");
            case CODE -> List.of("EXPLANATION", "CODE_LAB");
            case VISUAL -> List.of("EXPLANATION", "ANIMATION");
            case AUTO, MIXED -> null;
        };
        if ((expectedAgents != null && !expectedAgents.equals(plan.specialistAgents()))
                || (requestedMode == TutorTeachingMode.MIXED
                    && enabledActivities.size() == 4
                    && plan.specialistAgents().size() < 2)) {
            throw invalidAiResponse("教学计划与请求模式不匹配");
        }
        if (trace == null || trace.size() != plan.specialistAgents().size()) {
            throw invalidAiResponse("专家执行轨迹与教学计划不匹配");
        }
        Map<String, Integer> producedByAgent = new HashMap<>();
        for (PreparedActivity activity : activities) {
            String agent = switch (activity.activityType()) {
                case QUIZ -> "QUIZ";
                case CODE_LAB -> "CODE_LAB";
                case ANIMATION -> "ANIMATION";
            };
            producedByAgent.merge(agent, 1, Integer::sum);
        }
        int artifactCount = 0;
        for (int index = 0; index < trace.size(); index++) {
            AiTutorSpecialistInvocation item = trace.get(index);
            String expectedAgent = plan.specialistAgents().get(index);
            if (item == null || !expectedAgent.equals(item.agent())
                    || !SPECIALIST_STATUSES.contains(item.status())
                    || item.durationMs() == null || item.durationMs() < 0
                    || item.artifactCount() == null || item.artifactCount() < 0
                    || item.artifactCount() > 1
                    || (item.errorCode() != null
                        && !SAFE_ERROR_CODE.matcher(item.errorCode()).matches())
                    || ("SUCCEEDED".equals(item.status()) && item.errorCode() != null)
                    || !isValidSpecialistOutcome(item)) {
                throw invalidAiResponse("专家执行轨迹字段不合法");
            }
            int expectedArtifacts = producedByAgent.getOrDefault(item.agent(), 0);
            if (("EXPLANATION".equals(item.agent())
                    && (!"SUCCEEDED".equals(item.status()) || item.artifactCount() != 0))
                    || (!"EXPLANATION".equals(item.agent())
                        && item.artifactCount() != expectedArtifacts)) {
                throw invalidAiResponse("专家产物数量与已验证活动不一致");
            }
            artifactCount += item.artifactCount();
        }
        if (artifactCount != activities.size()) {
            throw invalidAiResponse("专家产物总数与互动活动数量不一致");
        }
    }

    /**
     * A specialist may return a deterministic, safe activity while reporting that the
     * provider failed or timed out.  This is deliberately narrower than the generic
     * error-code check: only the matching specialist fallback marker may carry one
     * artifact.  Ordinary unsuccessful invocations must carry zero artifacts.
     */
    private boolean isSpecialistFallbackArtifact(AiTutorSpecialistInvocation item) {
        if (item.artifactCount() == null || item.artifactCount() != 1
                || !("FAILED".equals(item.status()) || "TIMED_OUT".equals(item.status()))) {
            return false;
        }
        String expected = switch (item.agent()) {
            case "QUIZ" -> "QUIZ_FALLBACK_USED";
            case "CODE_LAB" -> "CODE_LAB_FALLBACK_USED";
            case "ANIMATION" -> "ANIMATION_FALLBACK_USED";
            default -> null;
        };
        return expected != null && expected.equals(item.errorCode());
    }

    /**
     * Keep the specialist outcome vocabulary deliberately closed.  A specialist
     * can be unavailable without producing an activity, and an activity specialist
     * can be skipped when the AI response hit the content-block budget.  Both are
     * accepted only with their exact, agent-scoped machine code; all other
     * unsuccessful outcomes must use the matching one-artifact fallback form.
     */
    private boolean isValidSpecialistOutcome(AiTutorSpecialistInvocation item) {
        if ("SUCCEEDED".equals(item.status())) {
            return true;
        }
        if ("SKIPPED".equals(item.status())) {
            return !"EXPLANATION".equals(item.agent())
                    && item.artifactCount() == 0
                    && "CONTENT_BLOCK_LIMIT".equals(item.errorCode());
        }
        if (("FAILED".equals(item.status()) || "TIMED_OUT".equals(item.status()))
                && item.artifactCount() == 0) {
            return unavailableErrorCode(item.agent()).equals(item.errorCode());
        }
        return isSpecialistFallbackArtifact(item);
    }

    private String unavailableErrorCode(String agent) {
        return switch (agent) {
            case "QUIZ" -> "QUIZ_UNAVAILABLE";
            case "CODE_LAB" -> "CODE_LAB_UNAVAILABLE";
            case "ANIMATION" -> "ANIMATION_UNAVAILABLE";
            default -> "";
        };
    }

    private Map<String, String> approvedContextNames(InternalGraphRagContextResponse context) {
        LinkedHashMap<String, String> names = new LinkedHashMap<>();
        addContextNames(names, List.of(context.knowledgePoint()));
        addContextNames(names, context.prerequisites());
        addContextNames(names, context.successors());
        addContextNames(names, context.relatedNodes());
        return Map.copyOf(names);
    }

    private void addContextNames(Map<String, String> names, List<InternalGraphNode> nodes) {
        for (InternalGraphNode node : nodes) {
            String existing = names.putIfAbsent(node.id(), node.name().trim());
            if (existing != null && !existing.equals(node.name().trim())) {
                throw new BusinessException(
                        ErrorCode.GRAPH_SERVICE_UNAVAILABLE,
                        "知识图谱上下文包含冲突的知识点名称");
            }
        }
    }

    private ContentBlockType activityBlockType(TutorActivityType type) {
        return switch (type) {
            case QUIZ -> ContentBlockType.QUIZ;
            case CODE_LAB -> ContentBlockType.CODE_LAB;
            case ANIMATION -> ContentBlockType.ANIMATION;
        };
    }

    private List<TutorSourceView> validateSources(List<Map<String, Object>> input, Long courseId, String knowledgePointId) {
        if (input == null || input.isEmpty() || input.size()>MAX_TUTOR_ITEMS) {
            throw invalidAiResponse("AI 导师回复缺少已审核来源");
        }
        List<TutorSourceView> result = new ArrayList<>();
        for (Map<String, Object> source : input) {
            if (source == null || !isApprovedSource(source)) {
                throw invalidAiResponse("AI 导师返回了未审核来源");
            }
            requireMapBound(source,"来源");
            if(tooLong(stringOrNull(first(source,"title")),MAX_TUTOR_TEXT_CHARS)
                    ||tooLong(stringOrNull(first(source,"locator")),MAX_TUTOR_TEXT_CHARS)
                    ||tooLong(stringOrNull(first(source,"excerpt")),MAX_TUTOR_TEXT_CHARS))
                throw invalidAiResponse("来源文本过长");
            result.add(new TutorSourceView(
                    approvedResourceId(asLong(first(source, "resource_id", "resourceId")), courseId, knowledgePointId),
                    stringOrDefault(first(source, "title"), "知识图谱来源"),
                    stringOrNull(first(source, "locator")),
                    stringOrNull(first(source, "excerpt")),
                    true
            ));
        }
        return List.copyOf(result);
    }

    private Long approvedResourceId(Long resourceId, Long courseId, String knowledgePointId) {
        if (resourceId == null) return null;
        approvedResource(resourceId, courseId, knowledgePointId);
        return resourceId;
    }

    private TeachingResourceEntity approvedResource(Long resourceId, Long courseId, String knowledgePointId) {
        TeachingResourceEntity resource = teachingResourceMapper.selectById(resourceId);
        if (resource == null || resource.getReviewStatus() != ReviewStatus.APPROVED
                || !courseId.equals(resource.getCourseId())
                || teachingResourceMapper.countKnowledgePointBinding(resourceId, knowledgePointId) != 1) {
            throw invalidAiResponse("来源引用的教学资源未审核或未绑定当前知识点");
        }
        return resource;
    }

    private Map<String, Object> sanitizedMetadata(Map<String, Object> input) {
        if (input == null || input.isEmpty()) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        input.forEach((key, value) -> {
            if (key == null || value == null) return;
            String normalized = key.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
            if (normalized.equals("reviewed") || normalized.equals("approved")
                    || normalized.equals("url") || normalized.equals("src")
                    || normalized.equals("href") || normalized.endsWith("url")) return;
            result.put(key, value);
        });
        return Map.copyOf(result);
    }

    private List<CognitiveLabelView> validateLabels(List<AiCognitiveLabel> input,Set<String> allowedKnowledgePointIds) {
        if (input == null || input.size()>MAX_TUTOR_ITEMS) {
            throw invalidAiResponse("认知标签字段缺失");
        }
        List<CognitiveLabelView> result = new ArrayList<>();
        Set<String> seenKnowledgePointIds=new java.util.HashSet<>();
        for (AiCognitiveLabel label : input) {
            if (label == null
                    || label.knowledgePointId() == null
                    || label.knowledgePointId().isBlank()
                    || label.knowledgePointId().length() > 64
                    || !allowedKnowledgePointIds.contains(label.knowledgePointId().trim())
                    || !seenKnowledgePointIds.add(label.knowledgePointId().trim())
                    || label.status() == null
                    || label.confidence() == null
                    || label.confidence().signum() < 0
                    || label.confidence().compareTo(java.math.BigDecimal.ONE) > 0
                    || label.evidence() == null
                    || label.evidence().isBlank()
                    || label.evidence().length() > 2000) {
                throw invalidAiResponse("认知标签格式不合法");
            }
            MasteryStatus status;
            try {
                status = MasteryStatus.valueOf(label.status().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw invalidAiResponse("认知标签状态不受支持");
            }
            result.add(new CognitiveLabelView(
                    label.knowledgePointId().trim(),
                    status,
                    label.confidence(),
                    label.evidence().trim()
            ));
        }
        return List.copyOf(result);
    }

    private void revalidateResourcesAtCommit(ValidatedAiReply validated,Long courseId,String knowledgePointId){
        validated.sources().stream().map(TutorSourceView::resourceId).filter(Objects::nonNull)
                .distinct().sorted(Comparator.naturalOrder()).forEach(resourceId->{
                    TeachingResourceEntity resource=teachingResourceMapper.selectByIdForUpdate(resourceId);
                    if(resource==null||resource.getReviewStatus()!=ReviewStatus.APPROVED
                            ||!courseId.equals(resource.getCourseId())
                            ||teachingResourceMapper.countKnowledgePointBinding(resourceId,knowledgePointId)!=1)
                        throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION,"教学资源审核或绑定状态已变化，请重试");
                });
    }

    private boolean tooLong(String value,int max){return value!=null&&value.length()>max;}
    private void requireMapBound(Map<String,Object> value,String field){
        if(value==null)return;
        if(value.size()>MAX_TUTOR_ITEMS||value.keySet().stream().anyMatch(key->key==null||key.isBlank()||key.length()>100))
            throw invalidAiResponse(field+"结构过大");
        requireJsonBytes(value,MAX_TUTOR_MAP_JSON_BYTES,field);
    }
    private void requirePersistedJsonBound(Object value,String field){requireJsonBytes(value,MAX_TUTOR_PERSISTED_JSON_BYTES,field);}
    private void requireJsonBytes(Object value,int max,String field){
        try{if(objectMapper.writeValueAsBytes(value).length>max)throw invalidAiResponse(field+"过大");}
        catch(JsonProcessingException exception){throw invalidAiResponse(field+"无法序列化");}
    }

    private IdempotencyClaim claim(
            Long userId,
            String operationId,
            String idempotencyKey,
            String requestHash
    ) {
        try {
            IdempotencyClaim result = transactionTemplate.execute(status -> {
                IdempotencyRecordEntity existing = findIdempotency(userId, operationId, idempotencyKey);
                if (existing != null && existing.getExpiresAt().isBefore(LocalDateTime.now())) {
                    idempotencyMapper.deleteById(existing.getId());
                    existing = null;
                }
                if (existing != null) {
                    return replayOrConflict(existing, requestHash);
                }
                IdempotencyRecordEntity record = new IdempotencyRecordEntity();
                record.setUserId(userId);
                record.setOperationId(operationId);
                record.setIdempotencyKey(idempotencyKey);
                record.setRequestHash(requestHash);
                record.setExpiresAt(LocalDateTime.now().plusHours(24));
                idempotencyMapper.insert(record);
                return new IdempotencyClaim(record.getId(), null);
            });
            return Objects.requireNonNull(result);
        } catch (DuplicateKeyException exception) {
            IdempotencyRecordEntity existing = findIdempotency(userId, operationId, idempotencyKey);
            if (existing == null) {
                throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION, "重复请求正在处理");
            }
            return replayOrConflict(existing, requestHash);
        }
    }

    private IdempotencyClaim replayOrConflict(IdempotencyRecordEntity existing, String requestHash) {
        if (!existing.getRequestHash().equals(requestHash)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT, "同一个幂等键不能用于不同请求");
        }
        if (existing.getResponseJson() == null) {
            throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION, "相同请求正在处理中");
        }
        try {
            return new IdempotencyClaim(
                    existing.getId(),
                    objectMapper.readValue(existing.getResponseJson(), TutorReply.class)
            );
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "无法恢复幂等请求结果");
        }
    }

    private IdempotencyRecordEntity findIdempotency(Long userId, String operationId, String idempotencyKey) {
        return idempotencyMapper.selectOne(new LambdaQueryWrapper<IdempotencyRecordEntity>()
                .eq(IdempotencyRecordEntity::getUserId, userId)
                .eq(IdempotencyRecordEntity::getOperationId, operationId)
                .eq(IdempotencyRecordEntity::getIdempotencyKey, idempotencyKey));
    }

    private void releaseClaim(Long recordId) {
        transactionTemplate.executeWithoutResult(status -> {
            IdempotencyRecordEntity record = idempotencyMapper.selectById(recordId);
            if (record != null && record.getResponseJson() == null) {
                idempotencyMapper.deleteById(recordId);
            }
        });
    }

    private TutorSessionEntity requireOwnedSession(AuthenticatedUser principal, Long sessionId) {
        TutorSessionEntity entity = sessionMapper.selectById(sessionId);
        if (entity == null || !entity.getStudentId().equals(principal.id())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "导师会话不存在");
        }
        courses.requireView(principal, entity.getCourseId());
        return entity;
    }

    private TutorMessageEntity requireMessage(Long messageId) {
        TutorMessageEntity entity = messageMapper.selectById(messageId);
        if (entity == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "导师消息保存失败");
        }
        return entity;
    }

    private List<TutorMessageEntity> listMessageEntities(Long sessionId) {
        return messageMapper.selectList(new LambdaQueryWrapper<TutorMessageEntity>()
                .eq(TutorMessageEntity::getSessionId, sessionId)
                .orderByAsc(TutorMessageEntity::getCreatedAt)
                .orderByAsc(TutorMessageEntity::getId));
    }

    private long messageCount(Long sessionId) {
        return messageMapper.selectCount(new LambdaQueryWrapper<TutorMessageEntity>()
                .eq(TutorMessageEntity::getSessionId, sessionId));
    }

    private List<AiTutorHistoryMessage> toAiHistory(List<TutorMessageEntity> allMessages) {
        return toAiHistoryForPrompt(allMessages);
    }

    static List<AiTutorHistoryMessage> toAiHistoryForPrompt(List<TutorMessageEntity> allMessages) {
        int from = Math.max(0, allMessages.size() - MAX_HISTORY_MESSAGES);
        int remainingChars = MAX_HISTORY_TOTAL_CHARS;
        List<AiTutorHistoryMessage> result = new ArrayList<>();
        for (int index = allMessages.size() - 1; index >= from && remainingChars > 0; index--) {
            TutorMessageEntity message = allMessages.get(index);
            String content = message.getContent();
            if (content == null || content.isBlank()) {
                continue;
            }
            int limit = Math.min(MAX_HISTORY_MESSAGE_CHARS, remainingChars);
            String promptContent = abbreviateHistory(content, limit);
            result.add(0, new AiTutorHistoryMessage(
                    message.getMessageRole() == TutorMessageRole.USER ? "user" : "assistant",
                    promptContent
            ));
            remainingChars -= promptContent.length();
        }
        return List.copyOf(result);
    }

    private static String abbreviateHistory(String content, int maxChars) {
        if (content.length() <= maxChars) {
            return content;
        }
        if (maxChars <= 3) {
            return content.substring(0, maxChars);
        }
        String marker = "\n…\n";
        int available = maxChars - marker.length();
        int leading = (available + 1) / 2;
        int trailing = available - leading;
        return content.substring(0, leading)
                + marker
                + content.substring(content.length() - trailing);
    }

    private TutorSessionView toSessionView(TutorSessionEntity entity, boolean includeMessages) {
        List<TutorMessageView> messages = includeMessages
                ? listMessageEntities(entity.getId()).stream().map(this::toMessageView).toList()
                : List.of();
        return new TutorSessionView(
                entity.getId(),
                entity.getCourseId(),
                entity.getStudentId(),
                new KnowledgePointBinding(entity.getKnowledgePointId(), entity.getKnowledgePointName()),
                entity.getStatus(),
                entity.getSummary(),
                messages,
                toInstant(entity.getCreatedAt()),
                toInstant(entity.getUpdatedAt())
        );
    }

    private TutorMessageView toMessageView(TutorMessageEntity entity) {
        return new TutorMessageView(
                entity.getId(),
                entity.getMessageRole(),
                entity.getContent(),
                readJsonList(entity.getContentBlocksJson(), BLOCK_LIST),
                readJsonList(entity.getSourcesJson(), SOURCE_LIST),
                toInstant(entity.getCreatedAt())
        );
    }

    private <T> List<T> readJsonList(String json, TypeReference<List<T>> type) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "导师消息结构化数据损坏");
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "导师消息序列化失败");
        }
    }

    private boolean isApprovedSource(Map<String, Object> source) {
        Object reviewed = first(source, "reviewed");
        Object reviewStatus = first(source, "review_status", "reviewStatus");
        Object rightsStatus = first(source, "rights_status", "rightsStatus");
        // Fail closed: the AI boundary must provide both the boolean assertion and
        // the graph review state plus C's authoritative copyright decision. Contradictory,
        // restricted, or legacy incomplete provenance is invalid and cannot enter a reply.
        return Boolean.TRUE.equals(reviewed)
                && reviewStatus != null
                && "APPROVED".equalsIgnoreCase(reviewStatus.toString())
                && rightsStatus != null
                && "ALLOWED".equalsIgnoreCase(rightsStatus.toString());
    }

    private Object first(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
        }
        return null;
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException exception) {
            throw invalidAiResponse("来源 resourceId 格式不合法");
        }
    }

    private String stringOrNull(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private String stringOrDefault(Object value, String defaultValue) {
        String text = stringOrNull(value);
        return text == null ? defaultValue : text;
    }

    private Map<String, Object> withoutNullValues(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        input.forEach((key, value) -> {
            if (key != null && value != null) {
                result.put(key, value);
            }
        });
        return result;
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void requireStudent(AuthenticatedUser principal) {
        if (principal.role() != UserRole.STUDENT) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "AI 导师会话仅对学生开放");
        }
    }

    private BusinessException invalidAiResponse(String detail) {
        return new BusinessException(
                ErrorCode.AI_SERVICE_INVALID_RESPONSE,
                "AI 导师返回的数据未通过业务校验",
                Map.of("reason", detail)
        );
    }

    private java.time.Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(DATABASE_ZONE).toInstant();
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record IdempotencyClaim(Long recordId, TutorReply replay) {
    }

    private record ValidatedAiReply(
            String answer,
            List<PreparedContentBlock> blocks,
            List<TutorSourceView> sources,
            List<CognitiveLabelView> labels,
            String suggestedNextStep,
            String modelVersion,
            String promptVersion,
            List<PreparedActivity> activities,
            AiTutorTeachingPlan teachingPlan,
            List<AiTutorSpecialistInvocation> specialistTrace
    ) {
    }

    private record PreparedContentBlock(ContentBlockView block, String activityRef) {
        private ContentBlockView resolve(Map<String, Long> activityIds) {
            if (activityRef == null) return block;
            Long activityId = activityIds.get(activityRef);
            if (activityId == null) {
                throw new BusinessException(
                        ErrorCode.INTERNAL_ERROR, "导师互动活动引用未能完成绑定");
            }
            return new ContentBlockView(
                    block.type(), block.blockId(), block.schemaVersion(), activityId,
                    block.content(), block.resourceId(), block.title(), block.language(),
                    block.metadata());
        }
    }
}
