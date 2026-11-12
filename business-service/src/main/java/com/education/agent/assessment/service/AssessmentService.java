package com.education.agent.assessment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.education.agent.admin.service.AuditLogService;
import com.education.agent.assessment.dto.AssessmentDtos.AnswerContent;
import com.education.agent.assessment.dto.AssessmentDtos.AssessmentDetail;
import com.education.agent.assessment.dto.AssessmentDtos.AssessmentSettings;
import com.education.agent.assessment.dto.AssessmentDtos.AssessmentSummary;
import com.education.agent.assessment.dto.AssessmentDtos.AssessmentUpsertRequest;
import com.education.agent.assessment.dto.AssessmentDtos.AttachmentPolicy;
import com.education.agent.assessment.dto.AssessmentDtos.KnowledgePointBinding;
import com.education.agent.assessment.dto.AssessmentDtos.ProgrammingConfig;
import com.education.agent.assessment.dto.AssessmentDtos.ProgrammingTestCaseDraft;
import com.education.agent.assessment.dto.AssessmentDtos.QuestionDraft;
import com.education.agent.assessment.dto.AssessmentDtos.QuestionOptionDraft;
import com.education.agent.assessment.dto.AssessmentDtos.QuestionUpsertRequest;
import com.education.agent.assessment.dto.AssessmentDtos.RubricItem;
import com.education.agent.assessment.entity.AssessmentEntity;
import com.education.agent.assessment.entity.AssessmentImportTaskEntity;
import com.education.agent.assessment.entity.AssessmentQuestionEntity;
import com.education.agent.assessment.entity.AssessmentVersionEntity;
import com.education.agent.assessment.entity.ProgrammingTestCaseEntity;
import com.education.agent.assessment.entity.QuestionKnowledgePointEntity;
import com.education.agent.assessment.entity.QuestionOptionEntity;
import com.education.agent.assessment.entity.QuestionRubricEntity;
import com.education.agent.assessment.mapper.AssessmentImportTaskMapper;
import com.education.agent.assessment.mapper.AssessmentMapper;
import com.education.agent.assessment.mapper.AssessmentQuestionMapper;
import com.education.agent.assessment.mapper.AssessmentVersionMapper;
import com.education.agent.assessment.mapper.ProgrammingTestCaseMapper;
import com.education.agent.assessment.mapper.QuestionKnowledgePointMapper;
import com.education.agent.assessment.mapper.QuestionOptionMapper;
import com.education.agent.assessment.mapper.QuestionRubricMapper;
import com.education.agent.assessment.model.AssessmentStatus;
import com.education.agent.assessment.model.AssessmentType;
import com.education.agent.assessment.model.QuestionType;
import com.education.agent.assessment.model.TaskStatus;
import com.education.agent.assessment.model.TestVisibility;
import com.education.agent.classroom.entity.ClassEntity;
import com.education.agent.classroom.mapper.ClassMapper;
import com.education.agent.classroom.mapper.ClassStudentMapper;
import com.education.agent.classroom.model.ClassStatus;
import com.education.agent.common.api.PageData;
import com.education.agent.common.error.BusinessException;
import com.education.agent.common.error.ErrorCode;
import com.education.agent.security.AuthenticatedUser;
import com.education.agent.file.service.FileStorageService;
import com.education.agent.integration.knowledge.InternalGraphRagContextResponse;
import com.education.agent.integration.knowledge.KnowledgeServiceClient;
import com.education.agent.submission.entity.SubmissionEntity;
import com.education.agent.submission.mapper.SubmissionMapper;
import com.education.agent.submission.model.SubmissionStatus;
import com.education.agent.notification.service.BusinessNotificationDispatcher;
import com.education.agent.tutor.entity.IdempotencyRecordEntity;
import com.education.agent.tutor.mapper.IdempotencyRecordMapper;
import com.education.agent.user.model.UserRole;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class AssessmentService {

    private static final ZoneId DATABASE_ZONE = ZoneId.of("Asia/Shanghai");
    private static final BigDecimal MAX_SCORE = new BigDecimal("999999.99");
    private static final long MAX_TEST_CASE_TEXT_UTF8_BYTES = 2L * 1024 * 1024;
    private static final Set<String> SUBMISSION_ATTACHMENT_EXTENSIONS = Set.of(
            ".pdf", ".docx", ".txt", ".md", ".png", ".jpg", ".jpeg", ".zip", ".py"
    );
    private static final Set<AssessmentStatus> STUDENT_VISIBLE_STATUSES = Set.of(
            AssessmentStatus.PUBLISHED,
            AssessmentStatus.CLOSED,
            AssessmentStatus.GRADING,
            AssessmentStatus.READY_TO_RELEASE,
            AssessmentStatus.RELEASED,
            AssessmentStatus.ARCHIVED
    );
    private static final Set<AssessmentStatus> DEADLINE_EDITABLE_STATUSES = Set.of(
            AssessmentStatus.PUBLISHED,
            AssessmentStatus.CLOSED,
            AssessmentStatus.GRADING,
            AssessmentStatus.READY_TO_RELEASE,
            AssessmentStatus.RELEASED
    );

    private final AssessmentMapper assessmentMapper;
    private final AssessmentQuestionMapper questionMapper;
    private final QuestionOptionMapper optionMapper;
    private final QuestionRubricMapper rubricMapper;
    private final ProgrammingTestCaseMapper testCaseMapper;
    private final QuestionKnowledgePointMapper knowledgePointMapper;
    private final AssessmentVersionMapper versionMapper;
    private final AssessmentImportTaskMapper importTaskMapper;
    private final ClassMapper classMapper;
    private final ClassStudentMapper classStudentMapper;
    private final IdempotencyRecordMapper idempotencyMapper;
    private final SubmissionMapper submissionMapper;
    private final KnowledgeServiceClient knowledgeServiceClient;
    private final com.education.agent.course.CourseAccessService courses;
    private final FileStorageService fileStorageService;
    private final AuditLogService auditLogService;
    private final BusinessNotificationDispatcher notificationDispatcher;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public AssessmentService(
            AssessmentMapper assessmentMapper,
            AssessmentQuestionMapper questionMapper,
            QuestionOptionMapper optionMapper,
            QuestionRubricMapper rubricMapper,
            ProgrammingTestCaseMapper testCaseMapper,
            QuestionKnowledgePointMapper knowledgePointMapper,
            AssessmentVersionMapper versionMapper,
            AssessmentImportTaskMapper importTaskMapper,
            ClassMapper classMapper,
            ClassStudentMapper classStudentMapper,
            IdempotencyRecordMapper idempotencyMapper,
            SubmissionMapper submissionMapper,
            KnowledgeServiceClient knowledgeServiceClient, com.education.agent.course.CourseAccessService courses,
            FileStorageService fileStorageService,
            AuditLogService auditLogService,
            BusinessNotificationDispatcher notificationDispatcher,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager
    ) {
        this.assessmentMapper = assessmentMapper;
        this.questionMapper = questionMapper;
        this.optionMapper = optionMapper;
        this.rubricMapper = rubricMapper;
        this.testCaseMapper = testCaseMapper;
        this.knowledgePointMapper = knowledgePointMapper;
        this.versionMapper = versionMapper;
        this.importTaskMapper = importTaskMapper;
        this.classMapper = classMapper;
        this.classStudentMapper = classStudentMapper;
        this.idempotencyMapper = idempotencyMapper;
        this.submissionMapper = submissionMapper;
        this.courses=courses;
        this.knowledgeServiceClient = knowledgeServiceClient;
        this.fileStorageService = fileStorageService;
        this.auditLogService = auditLogService;
        this.notificationDispatcher = notificationDispatcher;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public PageData<AssessmentSummary> list(
            AuthenticatedUser principal, Long courseId,
            long page,
            long size,
            AssessmentType assessmentType,
            Long classId,
            AssessmentStatus status,
            String keyword
    ) {
        if(courseId!=null) courses.requireView(principal,courseId);
        LambdaQueryWrapper<AssessmentEntity> query = new LambdaQueryWrapper<AssessmentEntity>()
                .inSql(courseId!=null, AssessmentEntity::getClassId,"SELECT id FROM edu_class WHERE course_id="+courseId)
                .eq(assessmentType != null, AssessmentEntity::getAssessmentType, assessmentType)
                .eq(classId != null, AssessmentEntity::getClassId, classId)
                .eq(status != null, AssessmentEntity::getStatus, status)
                .like(keyword != null && !keyword.isBlank(), AssessmentEntity::getTitle, keyword == null ? null : keyword.trim())
                .orderByDesc(AssessmentEntity::getCreatedAt)
                .orderByDesc(AssessmentEntity::getId);

        if (principal.role() == UserRole.TEACHER) {
            List<Long> classIds = teacherClassIds(principal.id());
            if (classIds.isEmpty()) {
                return emptyPage(page, size);
            }
            query.in(AssessmentEntity::getClassId, classIds);
        } else if (principal.role() == UserRole.STUDENT) {
            if (status != null && !STUDENT_VISIBLE_STATUSES.contains(status)) {
                return emptyPage(page, size);
            }
            List<Long> classIds = classStudentMapper.findClassIdsByStudentId(principal.id());
            if (classIds.isEmpty()) {
                return emptyPage(page, size);
            }
            query.in(AssessmentEntity::getClassId, classIds)
                    .inSql(AssessmentEntity::getClassId,"SELECT c.id FROM edu_class c JOIN edu_course course ON course.id=c.course_id WHERE course.status<>'DRAFT'")
                    .in(status == null, AssessmentEntity::getStatus, STUDENT_VISIBLE_STATUSES);
        }

        Page<AssessmentEntity> result = assessmentMapper.selectPage(Page.of(page, size), query);
        List<AssessmentSummary> items = result.getRecords().stream()
                .map(assessment -> toSummary(assessment, principal))
                .toList();
        return new PageData<>(items, result.getCurrent(), result.getSize(), result.getTotal(), result.getPages());
    }

    public AssessmentDetail get(AuthenticatedUser principal, Long assessmentId) {
        AssessmentEntity assessment = requireVisibleAssessment(principal, assessmentId);
        return toDetail(
                assessment,
                principal.role() != UserRole.STUDENT,
                mySubmissionStatus(assessment.getId(), principal)
        );
    }

    @Transactional
    public AssessmentDetail create(AuthenticatedUser principal, AssessmentUpsertRequest request) {
        requireAuthor(principal);
        validateDraftRequest(request);
        requireOwnedClass(principal, request.classId());
        for (QuestionUpsertRequest question : request.questions()) {
            if (question.id() != null) {
                throw validation("新建测评时题目 id 必须为空", Map.of("questionId", question.id()));
            }
        }

        AssessmentEntity entity = new AssessmentEntity();
        applyAssessmentRequest(entity, request);
        entity.setCreatorId(principal.id());
        entity.setStatus(AssessmentStatus.DRAFT);
        entity.setTotalScore(BigDecimal.ZERO);
        entity.setCurrentVersion(1);
        assessmentMapper.insert(entity);
        syncQuestions(entity.getId(), request.questions());
        recalculateTotal(entity.getId());
        AssessmentDetail created = toDetail(requireAssessment(entity.getId()), true);
        auditLogService.record(
                "ASSESSMENT_CREATED", "ASSESSMENT", entity.getId().toString(), principal.id(), true,
                Map.of("assessmentType", request.assessmentType().name(), "questionCount", request.questions().size())
        );
        return created;
    }

    @Transactional
    public AssessmentDetail update(
            AuthenticatedUser principal,
            Long assessmentId,
            AssessmentUpsertRequest request
    ) {
        requireAuthor(principal);
        validateDraftRequest(request);
        AssessmentEntity assessment = requireEditableForUpdate(principal, assessmentId);
        requireOwnedClass(principal, request.classId());
        applyAssessmentRequest(assessment, request);
        assessmentMapper.updateById(assessment);
        syncQuestions(assessmentId, request.questions());
        recalculateTotal(assessmentId);
        AssessmentDetail updated = toDetail(requireAssessment(assessmentId), true);
        auditLogService.record(
                "ASSESSMENT_DRAFT_SAVED", "ASSESSMENT", assessmentId.toString(), principal.id(), true,
                Map.of("questionCount", request.questions().size())
        );
        return updated;
    }

    @Transactional
    public AssessmentDetail updatePublishedDeadline(
            AuthenticatedUser principal,
            Long assessmentId,
            Instant dueAt
    ) {
        requireAuthor(principal);
        AssessmentEntity assessment = requireOwnedAssessmentForUpdate(principal, assessmentId);
        if (!DEADLINE_EDITABLE_STATUSES.contains(assessment.getStatus())) {
            throw conflict("草稿或已归档测评不能调整截止时间");
        }
        LocalDateTime nextDueAt = toLocal(dueAt);
        if (nextDueAt == null || !LocalDateTime.now().isBefore(nextDueAt)) {
            throw validation("新的截止时间必须晚于当前时间", Map.of("field", "dueAt"));
        }
        if (assessment.getStartAt() != null && !assessment.getStartAt().isBefore(nextDueAt)) {
            throw validation("截止时间必须晚于开始时间", Map.of("field", "dueAt"));
        }

        LocalDateTime previousDueAt = assessment.getDueAt();
        assessment.setDueAt(nextDueAt);
        assessmentMapper.updateById(assessment);
        updatePublishedSnapshotDeadline(assessment);

        int activeExamSessionsUpdated = 0;
        if (assessment.getAssessmentType() == AssessmentType.EXAM && assessment.getDurationMinutes() != null) {
            for (Long submissionId : submissionMapper.findOpenExamIds(assessmentId)) {
                SubmissionEntity submission = submissionMapper.selectForUpdate(submissionId);
                if (submission == null
                        || submission.getStatus() != SubmissionStatus.IN_PROGRESS
                        || submission.getExamSessionStartedAt() == null) {
                    continue;
                }
                LocalDateTime personalDeadline = submission.getExamSessionStartedAt()
                        .plusMinutes(assessment.getDurationMinutes());
                submission.setExamDeadlineAt(nextDueAt.isBefore(personalDeadline) ? nextDueAt : personalDeadline);
                submissionMapper.updateById(submission);
                activeExamSessionsUpdated += 1;
            }
        }

        Map<String, Object> auditDetails = new LinkedHashMap<>();
        auditDetails.put("previousDueAt", previousDueAt == null ? "UNSET" : toInstant(previousDueAt).toString());
        auditDetails.put("dueAt", dueAt.toString());
        auditDetails.put("activeExamSessionsUpdated", activeExamSessionsUpdated);
        auditLogService.record(
                "ASSESSMENT_DEADLINE_UPDATED", "ASSESSMENT", assessmentId.toString(), principal.id(), true,
                auditDetails
        );
        return toDetail(requireAssessment(assessmentId), true);
    }

    private void updatePublishedSnapshotDeadline(AssessmentEntity assessment) {
        AssessmentVersionEntity version = versionMapper.selectOne(
                new LambdaQueryWrapper<AssessmentVersionEntity>()
                        .eq(AssessmentVersionEntity::getAssessmentId, assessment.getId())
                        .eq(AssessmentVersionEntity::getVersionNo, assessment.getCurrentVersion())
        );
        if (version == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "已发布测评缺少版本快照");
        }
        try {
            JsonNode parsed = objectMapper.readTree(version.getSnapshotJson());
            if (!(parsed instanceof ObjectNode snapshot)) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "已发布测评版本快照格式错误");
            }
            snapshot.put("dueAt", toInstant(assessment.getDueAt()).toString());
            version.setSnapshotJson(objectMapper.writeValueAsString(snapshot));
            versionMapper.updateById(version);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "已发布测评版本快照格式错误");
        }
    }

    @Transactional
    public void delete(AuthenticatedUser principal, Long assessmentId) {
        requireAuthor(principal);
        AssessmentEntity assessment = requireOwnedAssessmentForUpdate(principal, assessmentId);
        if (assessment.getStatus() == AssessmentStatus.PARSING) {
            throw conflict("题目仍在解析，完成或失败后才能删除");
        }
        if (assessment.getStatus() == AssessmentStatus.CLOSED
                || assessment.getStatus() == AssessmentStatus.GRADING
                || assessment.getStatus() == AssessmentStatus.READY_TO_RELEASE) {
            throw conflict("测评正在批改或等待公布，当前不能删除");
        }
        AssessmentStatus previousStatus = assessment.getStatus();
        List<Long> sourceFileIds = importTaskMapper.selectList(
                        new LambdaQueryWrapper<AssessmentImportTaskEntity>()
                                .eq(AssessmentImportTaskEntity::getAssessmentId, assessmentId))
                .stream().map(AssessmentImportTaskEntity::getSourceFileId).distinct().toList();
        if (previousStatus == AssessmentStatus.DRAFT) {
            deleteAllQuestions(assessmentId);
            importTaskMapper.delete(new LambdaQueryWrapper<AssessmentImportTaskEntity>()
                    .eq(AssessmentImportTaskEntity::getAssessmentId, assessmentId));
            versionMapper.delete(new LambdaQueryWrapper<AssessmentVersionEntity>()
                    .eq(AssessmentVersionEntity::getAssessmentId, assessmentId));
        }
        assessmentMapper.deleteById(assessmentId);
        if (previousStatus == AssessmentStatus.DRAFT) {
            sourceFileIds.forEach(fileStorageService::deleteUnreferencedManaged);
        }
        auditLogService.record(
                "ASSESSMENT_DELETED", "ASSESSMENT", assessmentId.toString(), principal.id(), true,
                Map.of("previousStatus", previousStatus.name(),
                        "businessRecordsPreserved", previousStatus != AssessmentStatus.DRAFT)
        );
    }

    @Transactional
    public AssessmentDetail copy(AuthenticatedUser principal, Long assessmentId) {
        requireAuthor(principal);
        AssessmentEntity source = requireOwnedAssessment(principal, assessmentId, false);
        AssessmentDetail sourceDetail = toDetail(source, true);
        String copyTitle = source.getTitle() + "（副本）";
        if (copyTitle.length() > 200) {
            copyTitle = source.getTitle().substring(0, Math.max(1, 196)) + "（副本）";
        }
        AssessmentUpsertRequest request = new AssessmentUpsertRequest(
                source.getAssessmentType(),
                source.getClassId(),
                copyTitle,
                source.getDescription(),
                toInstant(source.getStartAt()),
                toInstant(source.getDueAt()),
                source.getDurationMinutes(),
                sourceDetail.settings(),
                sourceDetail.questions().stream().map(this::copyQuestionRequest).toList()
        );
        return create(principal, request);
    }

    @Transactional
    public QuestionDraft createQuestion(
            AuthenticatedUser principal,
            Long assessmentId,
            QuestionUpsertRequest request
    ) {
        requireAuthor(principal);
        requireEditableForUpdate(principal, assessmentId);
        validateDraftQuestions(List.of(request));
        if (request.id() != null) {
            throw validation("新题目的 id 必须为空", Map.of("questionId", request.id()));
        }
        if (questionMapper.selectCount(new LambdaQueryWrapper<AssessmentQuestionEntity>()
                .eq(AssessmentQuestionEntity::getAssessmentId, assessmentId)
                .and(wrapper -> wrapper.eq(AssessmentQuestionEntity::getClientKey, request.clientKey())
                        .or().eq(AssessmentQuestionEntity::getPositionNo, request.position()))) > 0) {
            throw conflict("题目 clientKey 或位置已经存在");
        }
        Long questionId = saveQuestion(assessmentId, request, null);
        recalculateTotal(assessmentId);
        return toQuestionDraft(requireQuestion(questionId));
    }

    @Transactional
    public QuestionDraft updateQuestion(
            AuthenticatedUser principal,
            Long assessmentId,
            Long questionId,
            QuestionUpsertRequest request
    ) {
        requireAuthor(principal);
        requireEditableForUpdate(principal, assessmentId);
        validateDraftQuestions(List.of(request));
        AssessmentQuestionEntity existing = requireQuestionInAssessment(assessmentId, questionId);
        AssessmentQuestionEntity duplicate = questionMapper.selectOne(new LambdaQueryWrapper<AssessmentQuestionEntity>()
                .eq(AssessmentQuestionEntity::getAssessmentId, assessmentId)
                .eq(AssessmentQuestionEntity::getClientKey, request.clientKey())
                .ne(AssessmentQuestionEntity::getId, questionId));
        if (duplicate != null) {
            throw conflict("题目 clientKey 已经存在");
        }
        AssessmentQuestionEntity samePosition = questionMapper.selectOne(new LambdaQueryWrapper<AssessmentQuestionEntity>()
                .eq(AssessmentQuestionEntity::getAssessmentId, assessmentId)
                .eq(AssessmentQuestionEntity::getPositionNo, request.position())
                .ne(AssessmentQuestionEntity::getId, questionId));
        if (samePosition != null) {
            throw conflict("题目位置已经存在，请使用排序接口");
        }
        saveQuestion(assessmentId, request, existing.getId());
        recalculateTotal(assessmentId);
        return toQuestionDraft(requireQuestion(questionId));
    }

    @Transactional
    public void deleteQuestion(AuthenticatedUser principal, Long assessmentId, Long questionId) {
        requireAuthor(principal);
        requireEditableForUpdate(principal, assessmentId);
        requireQuestionInAssessment(assessmentId, questionId);
        deleteQuestionChildren(questionId);
        questionMapper.deleteById(questionId);
        normalizePositions(assessmentId);
        recalculateTotal(assessmentId);
    }

    @Transactional
    public void reorderQuestions(AuthenticatedUser principal, Long assessmentId, List<Long> questionIds) {
        requireAuthor(principal);
        requireEditableForUpdate(principal, assessmentId);
        List<AssessmentQuestionEntity> existing = questions(assessmentId);
        Set<Long> expected = existing.stream().map(AssessmentQuestionEntity::getId).collect(java.util.stream.Collectors.toSet());
        Set<Long> supplied = new HashSet<>(questionIds);
        if (questionIds.size() != supplied.size() || !expected.equals(supplied)) {
            throw validation("排序必须且只能包含该测评的全部题目", Map.of(
                    "expectedQuestionCount", expected.size(), "suppliedQuestionCount", questionIds.size()
            ));
        }
        questionMapper.movePositionsOutOfWay(assessmentId);
        for (int index = 0; index < questionIds.size(); index++) {
            AssessmentQuestionEntity question = requireQuestion(questionIds.get(index));
            question.setPositionNo(index + 1);
            questionMapper.updateById(question);
        }
    }

    public AssessmentDetail publish(
            AuthenticatedUser principal,
            Long assessmentId,
            String idempotencyKey
    ) {
        requireAuthor(principal);
        String operationId = "ASSESSMENT_PUBLISH:" + assessmentId;
        String requestHash = sha256(operationId);
        PublishClaim claim = claimPublish(principal.id(), operationId, idempotencyKey, requestHash);
        if (claim.replay() != null) {
            return claim.replay();
        }
        try {
            PublicationValidation validation = preparePublication(principal, assessmentId);
            AssessmentDetail published = transactionTemplate.execute(status -> {
                AssessmentEntity assessment = requireOwnedAssessmentForUpdate(principal, assessmentId);
                if (assessment.getStatus() != AssessmentStatus.DRAFT) {
                    throw conflict("只有草稿状态可以发布");
                }
                if (importTaskMapper.selectCount(new LambdaQueryWrapper<AssessmentImportTaskEntity>()
                        .eq(AssessmentImportTaskEntity::getAssessmentId, assessmentId)
                        .in(AssessmentImportTaskEntity::getStatus, TaskStatus.PENDING, TaskStatus.RUNNING)) > 0) {
                    throw conflict("题目文件仍在解析，暂时不能发布");
                }
                AssessmentDetail draft = toDetail(assessment, true);
                validateForPublish(draft);
        if(draft.questions().stream().anyMatch(q->q.questionType()==QuestionType.PROGRAMMING)) courses.requireCode(courses.classCourse(assessment.getClassId()));
                if (!validation.draftHash().equals(publicationDraftHash(draft))) {
                    throw new BusinessException(
                            ErrorCode.CONCURRENT_MODIFICATION,
                            "测评在知识点校验期间已被修改，请重新发布"
                    );
                }
                confirmCanonicalKnowledgePointNames(draft, validation.canonicalNames());
                assessment.setStatus(AssessmentStatus.PUBLISHED);
                assessmentMapper.updateById(assessment);
                AssessmentDetail response = toDetail(requireAssessment(assessmentId), true);

                AssessmentVersionEntity version = new AssessmentVersionEntity();
                version.setAssessmentId(assessmentId);
                version.setVersionNo(assessment.getCurrentVersion());
                version.setSnapshotJson(writeJson(buildPublicationSnapshot(response, validation)));
                version.setPublishedBy(principal.id());
                version.setPublishedAt(LocalDateTime.now());
                versionMapper.insert(version);

                IdempotencyRecordEntity record = idempotencyMapper.selectById(claim.recordId());
                if (record == null || record.getResponseJson() != null) {
                    throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION, "发布请求状态已改变");
                }
                record.setResponseStatus(200);
                record.setResponseJson(writeJson(response));
                idempotencyMapper.updateById(record);
                notificationDispatcher.onAuditEvent("ASSESSMENT_PUBLISHED", "ASSESSMENT", assessmentId.toString());
                return response;
            });
            AssessmentDetail result = Objects.requireNonNull(published);
            auditLogService.record(
                    "ASSESSMENT_PUBLISHED", "ASSESSMENT", assessmentId.toString(), principal.id(), true,
                    Map.of("version", result.currentVersion(), "questionCount", result.questionCount())
            );
            return result;
        } catch (RuntimeException exception) {
            releasePublishClaim(claim.recordId());
            throw exception;
        }
    }

    private PublicationValidation preparePublication(AuthenticatedUser principal, Long assessmentId) {
        AssessmentEntity assessment = requireOwnedAssessment(principal, assessmentId, false);
        if (assessment.getStatus() != AssessmentStatus.DRAFT) {
            throw conflict("只有草稿状态可以发布");
        }
        if (importTaskMapper.selectCount(new LambdaQueryWrapper<AssessmentImportTaskEntity>()
                .eq(AssessmentImportTaskEntity::getAssessmentId, assessmentId)
                .in(AssessmentImportTaskEntity::getStatus, TaskStatus.PENDING, TaskStatus.RUNNING)) > 0) {
            throw conflict("题目文件仍在解析，暂时不能发布");
        }
        AssessmentDetail draft = toDetail(assessment, true);
        validateForPublish(draft);

        Map<String, String> canonicalNames = new LinkedHashMap<>();
        Map<String, GraphPublicationMetadata> graphMetadata = new LinkedHashMap<>();
        String publicationGraphVersion = null;
        for (QuestionDraft question : draft.questions()) {
            for (KnowledgePointBinding binding : question.knowledgePoints()) {
                if (canonicalNames.containsKey(binding.id())) {
                    continue;
                }
                InternalGraphRagContextResponse context;
                try {
                    context = knowledgeServiceClient.ragContext(courses.classCourse(assessment.getClassId()), binding.id());
                } catch (BusinessException exception) {
                    if (exception.errorCode() == ErrorCode.RESOURCE_NOT_FOUND) {
                        throw validation(
                                "题目绑定的知识点不存在或尚未审核发布",
                                Map.of("knowledgePointId", binding.id(), "questionId", question.id())
                        );
                    }
                    throw exception;
                }
                if (context == null) {
                    throw new BusinessException(
                            ErrorCode.GRAPH_SERVICE_UNAVAILABLE,
                            "知识图谱返回了空响应"
                    );
                }
                String graphVersion = context.graphVersion() == null ? null : context.graphVersion().trim();
                if (context.knowledgePoint() == null
                        || !binding.id().equals(context.knowledgePoint().id())
                        || context.knowledgePoint().name() == null
                        || context.knowledgePoint().name().isBlank()
                        || graphVersion == null
                        || graphVersion.isBlank()
                        || "unknown".equalsIgnoreCase(graphVersion)
                        || context.contextStatus() == null) {
                    throw new BusinessException(
                            ErrorCode.GRAPH_SERVICE_UNAVAILABLE,
                            "知识图谱返回的知识点数据不完整"
                    );
                }
                if (context.knowledgePoint().nodeType() == null
                        || context.knowledgePoint().reviewStatus() == null
                        || context.knowledgePoint().contentVersion() == null
                        || context.knowledgePoint().contentVersion() < 1) {
                    throw new BusinessException(
                            ErrorCode.GRAPH_SERVICE_UNAVAILABLE,
                            "知识图谱返回的知识点审核元数据不完整"
                    );
                }
                if (!"knowledge_point".equals(context.knowledgePoint().nodeType())
                        || !"APPROVED".equals(context.knowledgePoint().reviewStatus())) {
                    throw validation(
                            "题目只能绑定已审核发布的知识点节点",
                            Map.of(
                                    "knowledgePointId", binding.id(),
                                    "nodeType", context.knowledgePoint().nodeType(),
                                    "reviewStatus", context.knowledgePoint().reviewStatus()
                            )
                    );
                }
                if (!"READY".equals(context.contextStatus())) {
                    throw validation(
                            "题目绑定的知识点缺少可发布的已审核图谱上下文",
                            Map.of("knowledgePointId", binding.id(), "contextStatus", context.contextStatus())
                    );
                }
                if (publicationGraphVersion == null) {
                    publicationGraphVersion = graphVersion;
                } else if (!publicationGraphVersion.equals(graphVersion)) {
                    throw new BusinessException(
                            ErrorCode.GRAPH_SERVICE_UNAVAILABLE,
                            "同一次发布读取到了不同的知识图谱版本，请稍后重试"
                    );
                }
                String canonicalName = context.knowledgePoint().name().trim();
                canonicalNames.put(binding.id(), canonicalName);
                graphMetadata.put(binding.id(), new GraphPublicationMetadata(
                        binding.id(), canonicalName, graphVersion, context.knowledgePoint().contentVersion()
                ));
            }
        }
        return new PublicationValidation(
                publicationDraftHash(draft),
                Map.copyOf(canonicalNames),
                publicationGraphVersion,
                Map.copyOf(graphMetadata)
        );
    }

    private JsonNode buildPublicationSnapshot(
            AssessmentDetail assessment,
            PublicationValidation validation
    ) {
        List<PublishedKnowledgePointBinding> bindings = new ArrayList<>();
        for (QuestionDraft question : assessment.questions()) {
            for (KnowledgePointBinding binding : question.knowledgePoints()) {
                GraphPublicationMetadata metadata = validation.graphMetadata().get(binding.id());
                if (metadata == null) {
                    throw new BusinessException(
                            ErrorCode.CONCURRENT_MODIFICATION,
                            "题目知识点绑定在发布期间已改变，请重新发布"
                    );
                }
                bindings.add(new PublishedKnowledgePointBinding(
                        question.id(), question.clientKey(), metadata.knowledgePointId(), metadata.canonicalName(),
                        metadata.graphVersion(), metadata.contentVersion()
                ));
            }
        }
        ObjectNode snapshot = objectMapper.valueToTree(assessment);
        if (validation.graphVersion() == null) {
            snapshot.putNull("graphVersion");
        } else {
            snapshot.put("graphVersion", validation.graphVersion());
        }
        snapshot.set("knowledgePointBindings", objectMapper.valueToTree(List.copyOf(bindings)));
        return snapshot;
    }

    private void confirmCanonicalKnowledgePointNames(
            AssessmentDetail draft,
            Map<String, String> canonicalNames
    ) {
        for (QuestionDraft question : draft.questions()) {
            for (KnowledgePointBinding binding : question.knowledgePoints()) {
                String canonicalName = canonicalNames.get(binding.id());
                if (canonicalName == null
                        || knowledgePointMapper.confirmCanonicalName(question.id(), binding.id(), canonicalName) != 1) {
                    throw new BusinessException(
                            ErrorCode.CONCURRENT_MODIFICATION,
                            "题目知识点绑定在发布期间已改变，请重新发布"
                    );
                }
            }
        }
    }

    private String publicationDraftHash(AssessmentDetail detail) {
        return sha256(writeJson(detail));
    }

    private void syncQuestions(Long assessmentId, List<QuestionUpsertRequest> requests) {
        List<AssessmentQuestionEntity> existing = questions(assessmentId);
        Map<Long, AssessmentQuestionEntity> byId = new LinkedHashMap<>();
        existing.forEach(question -> byId.put(question.getId(), question));
        Set<Long> retained = new HashSet<>();

        questionMapper.movePositionsOutOfWay(assessmentId);
        for (QuestionUpsertRequest request : requests) {
            Long existingId = request.id();
            if (existingId != null && !byId.containsKey(existingId)) {
                throw validation("题目 id 不属于当前测评", Map.of("questionId", existingId));
            }
            if (existingId != null && !retained.add(existingId)) {
                throw validation("题目 id 重复", Map.of("questionId", existingId));
            }
            saveQuestion(assessmentId, request, existingId);
        }

        for (AssessmentQuestionEntity question : existing) {
            if (!retained.contains(question.getId())) {
                deleteQuestionChildren(question.getId());
                questionMapper.deleteById(question.getId());
            }
        }
    }

    private Long saveQuestion(Long assessmentId, QuestionUpsertRequest request, Long existingId) {
        AssessmentQuestionEntity entity = existingId == null
                ? new AssessmentQuestionEntity()
                : requireQuestionInAssessment(assessmentId, existingId);
        entity.setAssessmentId(assessmentId);
        entity.setClientKey(request.clientKey().trim());
        if(request.questionType()==QuestionType.PROGRAMMING) courses.requireCode(courses.classCourse(requireAssessment(assessmentId).getClassId()));
        entity.setQuestionType(request.questionType());
        entity.setPositionNo(request.position());
        entity.setStem(request.stem().trim());
        entity.setScore(request.score());
        entity.setReferenceAnswerJson(writeNullableJson(request.referenceAnswer()));
        entity.setProgrammingConfigJson(writeNullableJson(request.programmingConfig()));
        entity.setAttachmentPolicyJson(writeNullableJson(request.attachmentPolicy()));
        entity.setAiGenerated(Boolean.TRUE.equals(request.aiGenerated()));
        if (existingId == null) {
            questionMapper.insert(entity);
        } else {
            questionMapper.updateById(entity);
            deleteQuestionChildren(existingId);
        }
        Long questionId = entity.getId();

        for (QuestionOptionDraft option : request.options()) {
            QuestionOptionEntity child = new QuestionOptionEntity();
            child.setQuestionId(questionId);
            child.setOptionKey(option.key().trim());
            child.setContent(option.content().trim());
            child.setIsCorrect(option.isCorrect());
            child.setPositionNo(option.position());
            optionMapper.insert(child);
        }
        for (RubricItem rubric : request.rubricItems()) {
            QuestionRubricEntity child = new QuestionRubricEntity();
            child.setQuestionId(questionId);
            child.setClientKey(rubric.clientKey().trim());
            child.setLabel(rubric.label().trim());
            child.setDescription(rubric.description().trim());
            child.setMaxScore(rubric.maxScore());
            child.setPositionNo(rubric.position());
            rubricMapper.insert(child);
        }
        ProgrammingConfig programming = request.programmingConfig();
        for (ProgrammingTestCaseDraft testCase : request.testCases()) {
            ProgrammingTestCaseEntity child = new ProgrammingTestCaseEntity();
            child.setQuestionId(questionId);
            child.setClientKey(testCase.clientKey().trim());
            child.setInputData(testCase.input());
            child.setExpectedOutput(testCase.expectedOutput());
            child.setScore(testCase.score());
            child.setVisibility(testCase.visibility());
            child.setCompareMode(programming == null ? null : programming.compareMode());
            child.setTimeLimitMs(programming == null ? 2000 : programming.timeLimitMs());
            child.setPositionNo(testCase.position());
            testCaseMapper.insert(child);
        }
        for (KnowledgePointBinding knowledgePoint : request.knowledgePoints()) {
            QuestionKnowledgePointEntity child = new QuestionKnowledgePointEntity();
            child.setQuestionId(questionId);
            child.setKnowledgePointId(knowledgePoint.id().trim());
            child.setKnowledgePointName(knowledgePoint.name().trim());
            child.setBindingSource(Boolean.TRUE.equals(request.aiGenerated()) ? "AI_SUGGESTED" : "TEACHER");
            knowledgePointMapper.insert(child);
        }
        return questionId;
    }

    private void applyAssessmentRequest(AssessmentEntity entity, AssessmentUpsertRequest request) {
        entity.setAssessmentType(request.assessmentType());
        entity.setClassId(request.classId());
        entity.setTitle(request.title().trim());
        entity.setDescription(trimToNull(request.description()));
        entity.setStartAt(toLocal(request.startAt()));
        entity.setDueAt(toLocal(request.dueAt()));
        entity.setDurationMinutes(request.durationMinutes());
        entity.setAutoGradeEnabled(request.settings().autoGradeEnabled());
        entity.setAutoReleaseEnabled(request.settings().autoReleaseEnabled());
        entity.setAiAttachmentGradeEnabled(request.settings().aiAttachmentGradeEnabled());
        entity.setSettingsJson(writeJson(request.settings()));
    }

    private void validateDraftRequest(AssessmentUpsertRequest request) {
        if (request.startAt() != null && request.dueAt() != null && !request.startAt().isBefore(request.dueAt())) {
            throw validation("开始时间必须早于截止时间", Map.of());
        }
        validateDraftQuestions(request.questions());
    }

    private void validateDraftQuestions(List<QuestionUpsertRequest> questions) {
        Set<String> clientKeys = new HashSet<>();
        Set<Integer> positions = new HashSet<>();
        BigDecimal totalScore = BigDecimal.ZERO;
        for (QuestionUpsertRequest question : questions) {
            validatePositiveScore(question.score(), "题目分值", question.clientKey());
            totalScore = totalScore.add(question.score());
            for (RubricItem rubric : question.rubricItems()) {
                validatePositiveScore(rubric.maxScore(), "评分点分值", question.clientKey());
            }
            for (ProgrammingTestCaseDraft testCase : question.testCases()) {
                validatePositiveScore(testCase.score(), "测试点分值", question.clientKey());
            }
            validateDraftRuntimeBounds(question);
            if (!clientKeys.add(question.clientKey().trim())) {
                throw validation("题目 clientKey 不能重复", Map.of("clientKey", question.clientKey()));
            }
            if (!positions.add(question.position())) {
                throw validation("题目位置不能重复", Map.of("position", question.position()));
            }
            ensureUnique(
                    question.options().stream().map(option -> option.key().trim()).toList(),
                    "选项 key 不能重复"
            );
            ensureUnique(
                    question.options().stream().map(QuestionOptionDraft::position).toList(),
                    "选项位置不能重复"
            );
            ensureUnique(
                    question.rubricItems().stream().map(item -> item.clientKey().trim()).toList(),
                    "评分点 clientKey 不能重复"
            );
            ensureUnique(
                    question.rubricItems().stream().map(RubricItem::position).toList(),
                    "评分点位置不能重复"
            );
            ensureUnique(
                    question.testCases().stream().map(item -> item.clientKey().trim()).toList(),
                    "测试点 clientKey 不能重复"
            );
            ensureUnique(
                    question.testCases().stream().map(ProgrammingTestCaseDraft::position).toList(),
                    "测试点位置不能重复"
            );
            ensureUnique(
                    question.knowledgePoints().stream().map(item -> item.id().trim()).toList(),
                    "知识点不能重复"
            );
        }
        validateTotalScore(totalScore);
    }

    private void validateDraftRuntimeBounds(QuestionUpsertRequest question) {
        if (question.questionType() == QuestionType.ATTACHMENT && question.attachmentPolicy() != null) {
            validateAttachmentExtensions(
                    question.attachmentPolicy().allowedExtensions(),
                    question.clientKey()
            );
        }
        if (question.questionType() != QuestionType.PROGRAMMING) {
            return;
        }
        if (question.testCases().size() > 100) {
            throw validation(
                    "Programming questions support at most 100 test cases",
                    Map.of("clientKey", question.clientKey())
            );
        }
        long textBytes = 0;
        for (ProgrammingTestCaseDraft testCase : question.testCases()) {
            if (testCase.input().length() > 65536 || testCase.expectedOutput().length() > 65536) {
                throw validation(
                        "Test-case input and expected output support at most 65536 characters each",
                        Map.of("clientKey", question.clientKey())
                );
            }
            textBytes += testCase.input().getBytes(StandardCharsets.UTF_8).length;
            textBytes += testCase.expectedOutput().getBytes(StandardCharsets.UTF_8).length;
            if (textBytes > MAX_TEST_CASE_TEXT_UTF8_BYTES) {
                throw validation(
                        "Test-case text for one question exceeds 2 MiB",
                        Map.of("clientKey", question.clientKey())
                );
            }
        }
    }

    private void validateAttachmentExtensions(List<String> extensions, String clientKey) {
        Set<String> normalized = new HashSet<>();
        for (String extension : extensions) {
            String value = extension == null ? "" : extension.trim().toLowerCase(java.util.Locale.ROOT);
            if (!value.matches("^\\.[a-z0-9]+$") || !normalized.add(value)) {
                throw validation(
                        "Attachment extensions must use unique .alphanumeric values",
                        Map.of("clientKey", clientKey)
                );
            }
            if (!SUBMISSION_ATTACHMENT_EXTENSIONS.contains(value)) {
                throw validation(
                        "Attachment extension is not supported by submission uploads",
                        Map.of("clientKey", clientKey, "extension", value)
                );
            }
        }
    }

    private void validatePositiveScore(BigDecimal value, String label, QuestionDraft question) {
        validatePositiveScore(value, label, question == null ? null : question.clientKey());
    }

    private void validatePositiveScore(BigDecimal value, String label, String clientKey) {
        if (value == null || value.signum() <= 0 || value.compareTo(MAX_SCORE) > 0 || value.scale() > 2) {
            Map<String, Object> details = clientKey == null
                    ? Map.of("field", label)
                    : Map.of("field", label, "clientKey", clientKey);
            throw validation(label + "必须大于 0、不超过 999999.99，且最多保留两位小数", details);
        }
    }

    private void validateTotalScore(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.compareTo(MAX_SCORE) > 0 || value.scale() > 2) {
            throw validation("测评总分不能超过 999999.99，且最多保留两位小数", Map.of("field", "totalScore"));
        }
    }

    private void validateForPublish(AssessmentDetail assessment) {
        if (assessment.questions().isEmpty()) {
            throw validation("至少需要一道题才能发布", Map.of());
        }
        if (assessment.startAt() != null
                && assessment.dueAt() != null
                && !assessment.startAt().isBefore(assessment.dueAt())) {
            throw validation("开始时间必须早于截止时间", Map.of());
        }
        if (assessment.assessmentType() == AssessmentType.EXAM) {
            if (assessment.startAt() == null) {
                throw validation("考试发布前必须设置开始时间", Map.of("field", "startAt"));
            }
            if (assessment.dueAt() == null) {
                throw validation("考试发布前必须设置截止时间", Map.of("field", "dueAt"));
            }
            if (assessment.durationMinutes() == null) {
                throw validation("考试发布前必须设置答题时长", Map.of("field", "durationMinutes"));
            }
        }
        if (assessment.assessmentType() == AssessmentType.HOMEWORK
                && Boolean.TRUE.equals(assessment.settings().antiCheatPolicy().enabled())) {
            throw validation("防作弊策略只适用于考试", Map.of("field", "settings.antiCheatPolicy.enabled"));
        }
        for (QuestionDraft question : assessment.questions()) {
            validatePositiveScore(question.score(), "题目分值", question);
            for (RubricItem rubric : question.rubricItems()) {
                validatePositiveScore(rubric.maxScore(), "评分点分值", question);
            }
            for (ProgrammingTestCaseDraft testCase : question.testCases()) {
                validatePositiveScore(testCase.score(), "测试点分值", question);
            }
            switch (question.questionType()) {
                case SINGLE_CHOICE -> validateChoice(question, true);
                case MULTIPLE_CHOICE -> validateChoice(question, false);
                case SUBJECTIVE -> {
                    validateSubjectiveReferenceAnswer(question);
                    validateRubricTotal(question);
                }
                case PROGRAMMING -> validateProgramming(question);
                case ATTACHMENT -> validateAttachmentQuestion(question);
            }
        }
        BigDecimal sum = assessment.questions().stream()
                .map(QuestionDraft::score)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        validateTotalScore(sum);
        if (sum.compareTo(assessment.totalScore()) != 0) {
            throw validation("测评总分与题目分值之和不一致", Map.of());
        }
    }

    private void validateChoice(QuestionDraft question, boolean single) {
        if (!question.rubricItems().isEmpty()
                || !question.testCases().isEmpty()
                || question.programmingConfig() != null
                || question.attachmentPolicy() != null) {
            throw questionValidation(question, "选择题包含了不适用的评分点、测试点、编程或附件配置");
        }
        if (question.options().size() < 2) {
            throw questionValidation(question, "选择题至少需要两个选项");
        }
        long correct = question.options().stream().filter(option -> Boolean.TRUE.equals(option.isCorrect())).count();
        if ((single && correct != 1) || (!single && correct < 1)) {
            throw questionValidation(question, single ? "单选题必须且只能有一个正确选项" : "多选题至少需要一个正确选项");
        }
        Set<String> correctKeys = question.options().stream()
                .filter(option -> Boolean.TRUE.equals(option.isCorrect()))
                .map(QuestionOptionDraft::key)
                .collect(java.util.stream.Collectors.toSet());
        AnswerContent answer = question.referenceAnswer();
        Set<String> referenceKeys = answer == null || answer.selectedOptionKeys() == null
                ? Set.of()
                : new HashSet<>(answer.selectedOptionKeys());
        if (!correctKeys.equals(referenceKeys)
                || (answer != null && (hasText(answer.textAnswer())
                    || hasText(answer.sourceCode())
                    || (answer.attachmentFileIds() != null && !answer.attachmentFileIds().isEmpty())))) {
            throw questionValidation(question, "选择题参考答案必须与正确选项完全一致，且不能包含其他答案字段");
        }
    }

    private void validateSubjectiveReferenceAnswer(QuestionDraft question) {
        if (!question.options().isEmpty()
                || !question.testCases().isEmpty()
                || question.programmingConfig() != null
                || question.attachmentPolicy() != null) {
            throw questionValidation(question, "主观题包含了不适用的选项、测试点、编程或附件配置");
        }
        AnswerContent answer = question.referenceAnswer();
        if (answer == null) {
            return;
        }
        if (!hasText(answer.textAnswer())
                || (answer.selectedOptionKeys() != null && !answer.selectedOptionKeys().isEmpty())
                || hasText(answer.sourceCode())
                || (answer.attachmentFileIds() != null && !answer.attachmentFileIds().isEmpty())) {
            throw questionValidation(question, "主观题参考答案可留空；填写时只能包含非空文本答案");
        }
    }

    private void validateRubricTotal(QuestionDraft question) {
        if (question.rubricItems().isEmpty()) {
            throw questionValidation(question, "主观题或附件题必须设置评分点");
        }
        BigDecimal total = question.rubricItems().stream()
                .map(RubricItem::maxScore)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(question.score()) != 0) {
            throw questionValidation(question, "评分点分值之和必须等于题目分值");
        }
    }

    private void validateProgramming(QuestionDraft question) {
        if (!question.options().isEmpty()
                || !question.rubricItems().isEmpty()
                || question.attachmentPolicy() != null) {
            throw questionValidation(question, "编程题包含了不适用的选项、评分点或附件配置");
        }
        if (question.programmingConfig() == null
                || !"PYTHON".equalsIgnoreCase(question.programmingConfig().language())) {
            throw questionValidation(question, "编程题必须使用受支持的 PYTHON 配置");
        }
        if (question.referenceAnswer() == null
                || question.referenceAnswer().sourceCode() == null
                || question.referenceAnswer().sourceCode().isBlank()) {
            throw questionValidation(question, "编程题必须设置参考代码");
        }
        AnswerContent answer = question.referenceAnswer();
        if ((answer.selectedOptionKeys() != null && !answer.selectedOptionKeys().isEmpty())
                || hasText(answer.textAnswer())
                || (answer.attachmentFileIds() != null && !answer.attachmentFileIds().isEmpty())) {
            throw questionValidation(question, "编程题参考答案只能填写源代码");
        }
        if (question.testCases().isEmpty()) {
            throw questionValidation(question, "编程题至少需要一个测试点");
        }
        if (question.testCases().size() > 100) {
            throw questionValidation(question, "Programming questions support at most 100 test cases");
        }
        long textBytes = 0;
        for (ProgrammingTestCaseDraft testCase : question.testCases()) {
            if (testCase.input().length() > 65536 || testCase.expectedOutput().length() > 65536) {
                throw questionValidation(
                        question,
                        "Test-case input and expected output support at most 65536 characters each"
                );
            }
            textBytes += testCase.input().getBytes(StandardCharsets.UTF_8).length;
            textBytes += testCase.expectedOutput().getBytes(StandardCharsets.UTF_8).length;
            if (textBytes > MAX_TEST_CASE_TEXT_UTF8_BYTES) {
                throw questionValidation(question, "Test-case text for one question exceeds 2 MiB");
            }
        }
        boolean hasPublic = question.testCases().stream()
                .anyMatch(testCase -> testCase.visibility() == TestVisibility.PUBLIC);
        boolean hasHidden = question.testCases().stream()
                .anyMatch(testCase -> testCase.visibility() == TestVisibility.HIDDEN);
        if (!hasPublic || !hasHidden) {
            throw questionValidation(question, "编程题至少需要一个公开测试点和一个隐藏测试点");
        }
        BigDecimal total = question.testCases().stream()
                .map(ProgrammingTestCaseDraft::score)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(question.score()) != 0) {
            throw questionValidation(question, "测试点分值之和必须等于题目分值");
        }
        if (question.programmingConfig().compareMode() == com.education.agent.assessment.model.CompareMode.FLOAT_TOLERANCE
                && question.programmingConfig().floatTolerance() == null) {
            throw questionValidation(question, "浮点比较模式必须设置容差");
        }
    }

    private void validateAttachmentQuestion(QuestionDraft question) {
        if (!question.options().isEmpty()
                || !question.testCases().isEmpty()
                || question.programmingConfig() != null) {
            throw questionValidation(question, "附件题包含了不适用的选项、测试点或编程配置");
        }
        AnswerContent answer = question.referenceAnswer();
        if (answer != null
                && ((answer.selectedOptionKeys() != null && !answer.selectedOptionKeys().isEmpty())
                    || hasText(answer.textAnswer())
                    || hasText(answer.sourceCode())
                    || (answer.attachmentFileIds() != null && !answer.attachmentFileIds().isEmpty()))) {
            throw questionValidation(question, "附件题不能预置文本、代码、选项或附件答案");
        }
        AttachmentPolicy policy = question.attachmentPolicy();
        if (policy == null) {
            throw questionValidation(question, "附件题必须设置提交文件规则");
        }
        Set<String> normalized = new HashSet<>();
        for (String extension : policy.allowedExtensions()) {
            String value = extension == null ? "" : extension.trim().toLowerCase(java.util.Locale.ROOT);
            if (!value.matches("^\\.[a-z0-9]+$") || !normalized.add(value)) {
                throw questionValidation(question, "附件扩展名必须是唯一的 .字母数字 格式");
            }
            if (!SUBMISSION_ATTACHMENT_EXTENSIONS.contains(value)) {
                throw questionValidation(question, "附件扩展名不在学生答题上传白名单中");
            }
        }
        validateRubricTotal(question);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private BusinessException questionValidation(QuestionDraft question, String message) {
        return validation(message, Map.of("questionId", question.id(), "clientKey", question.clientKey()));
    }

    private AssessmentDetail toDetail(AssessmentEntity assessment, boolean includeQuestions) {
        return toDetail(assessment, includeQuestions, null);
    }

    private AssessmentDetail toDetail(
            AssessmentEntity assessment,
            boolean includeQuestions,
            String mySubmissionStatus
    ) {
        ClassEntity classroom = classMapper.selectById(assessment.getClassId());
        List<QuestionDraft> questionViews = includeQuestions
                ? questions(assessment.getId()).stream().map(this::toQuestionDraft).toList()
                : List.of();
        int count = Math.toIntExact(questionMapper.selectCount(new LambdaQueryWrapper<AssessmentQuestionEntity>()
                .eq(AssessmentQuestionEntity::getAssessmentId, assessment.getId())));
        return new AssessmentDetail(
                assessment.getId(), assessment.getAssessmentType(), assessment.getTitle(), assessment.getClassId(),
                classroom == null ? null : classroom.getName(), assessment.getCreatorId(), assessment.getStatus(),
                assessment.getTotalScore(), toInstant(assessment.getStartAt()), toInstant(assessment.getDueAt()),
                assessment.getDurationMinutes(), count, mySubmissionStatus, toInstant(assessment.getCreatedAt()),
                toInstant(assessment.getUpdatedAt()), assessment.getDescription(), readSettings(assessment.getSettingsJson()),
                assessment.getCurrentVersion(), questionViews
        );
    }

    private AssessmentSummary toSummary(AssessmentEntity assessment, AuthenticatedUser principal) {
        AssessmentDetail detail = toDetail(
                assessment,
                false,
                mySubmissionStatus(assessment.getId(), principal)
        );
        return new AssessmentSummary(
                detail.id(), detail.assessmentType(), detail.title(), detail.classId(), detail.className(),
                detail.creatorId(), detail.status(), detail.totalScore(), detail.startAt(), detail.dueAt(),
                detail.durationMinutes(), detail.questionCount(), detail.mySubmissionStatus(), detail.createdAt(), detail.updatedAt()
        );
    }

    private String mySubmissionStatus(Long assessmentId, AuthenticatedUser principal) {
        if (principal.role() != UserRole.STUDENT) {
            return null;
        }
        SubmissionEntity submission = submissionMapper.findLatest(assessmentId, principal.id());
        return submission == null ? null : submission.getStatus().name();
    }

    private QuestionDraft toQuestionDraft(AssessmentQuestionEntity question) {
        List<QuestionOptionDraft> options = optionMapper.selectList(new LambdaQueryWrapper<QuestionOptionEntity>()
                        .eq(QuestionOptionEntity::getQuestionId, question.getId())
                        .orderByAsc(QuestionOptionEntity::getPositionNo).orderByAsc(QuestionOptionEntity::getId))
                .stream().map(option -> new QuestionOptionDraft(
                        option.getId(), option.getOptionKey(), option.getContent(), option.getIsCorrect(), option.getPositionNo()
                )).toList();
        List<RubricItem> rubrics = rubricMapper.selectList(new LambdaQueryWrapper<QuestionRubricEntity>()
                        .eq(QuestionRubricEntity::getQuestionId, question.getId())
                        .orderByAsc(QuestionRubricEntity::getPositionNo).orderByAsc(QuestionRubricEntity::getId))
                .stream().map(rubric -> new RubricItem(
                        rubric.getId(), rubric.getClientKey(), rubric.getLabel(), rubric.getDescription(),
                        rubric.getMaxScore(), rubric.getPositionNo()
                )).toList();
        ProgrammingConfig programming = readNullableJson(question.getProgrammingConfigJson(), ProgrammingConfig.class);
        List<ProgrammingTestCaseDraft> testCases = testCaseMapper.selectList(
                        new LambdaQueryWrapper<ProgrammingTestCaseEntity>()
                                .eq(ProgrammingTestCaseEntity::getQuestionId, question.getId())
                                .orderByAsc(ProgrammingTestCaseEntity::getPositionNo)
                                .orderByAsc(ProgrammingTestCaseEntity::getId))
                .stream().map(test -> new ProgrammingTestCaseDraft(
                        test.getId(), test.getClientKey(), test.getInputData(), test.getExpectedOutput(), test.getScore(),
                        test.getVisibility(), test.getPositionNo()
                )).toList();
        List<KnowledgePointBinding> knowledgePoints = knowledgePointMapper.findByQuestionId(question.getId())
                .stream().map(point -> new KnowledgePointBinding(
                        point.getKnowledgePointId(), point.getKnowledgePointName()
                )).toList();
        return new QuestionDraft(
                question.getId(), question.getClientKey(), question.getQuestionType(), question.getStem(), question.getScore(),
                question.getPositionNo(), options,
                readNullableJson(question.getReferenceAnswerJson(), AnswerContent.class), rubrics, programming, testCases,
                readNullableJson(question.getAttachmentPolicyJson(), AttachmentPolicy.class), knowledgePoints,
                question.getAiGenerated(), toInstant(question.getUpdatedAt())
        );
    }

    private QuestionUpsertRequest copyQuestionRequest(QuestionDraft question) {
        return new QuestionUpsertRequest(
                null, question.clientKey(), question.questionType(), question.stem(), question.score(), question.position(),
                question.options().stream().map(option -> new QuestionOptionDraft(
                        null, option.key(), option.content(), option.isCorrect(), option.position()
                )).toList(),
                question.referenceAnswer(),
                question.rubricItems().stream().map(rubric -> new RubricItem(
                        null, rubric.clientKey(), rubric.label(), rubric.description(), rubric.maxScore(), rubric.position()
                )).toList(),
                question.programmingConfig(),
                question.testCases().stream().map(test -> new ProgrammingTestCaseDraft(
                        null, test.clientKey(), test.input(), test.expectedOutput(), test.score(), test.visibility(), test.position()
                )).toList(),
                question.attachmentPolicy(), question.knowledgePoints(), question.aiGenerated()
        );
    }

    private void deleteAllQuestions(Long assessmentId) {
        for (AssessmentQuestionEntity question : questions(assessmentId)) {
            deleteQuestionChildren(question.getId());
        }
        questionMapper.delete(new LambdaQueryWrapper<AssessmentQuestionEntity>()
                .eq(AssessmentQuestionEntity::getAssessmentId, assessmentId));
    }

    private void deleteQuestionChildren(Long questionId) {
        optionMapper.delete(new LambdaQueryWrapper<QuestionOptionEntity>()
                .eq(QuestionOptionEntity::getQuestionId, questionId));
        rubricMapper.delete(new LambdaQueryWrapper<QuestionRubricEntity>()
                .eq(QuestionRubricEntity::getQuestionId, questionId));
        testCaseMapper.delete(new LambdaQueryWrapper<ProgrammingTestCaseEntity>()
                .eq(ProgrammingTestCaseEntity::getQuestionId, questionId));
        knowledgePointMapper.deleteByQuestionId(questionId);
    }

    private void normalizePositions(Long assessmentId) {
        List<AssessmentQuestionEntity> remaining = questions(assessmentId);
        questionMapper.movePositionsOutOfWay(assessmentId);
        for (int index = 0; index < remaining.size(); index++) {
            AssessmentQuestionEntity question = remaining.get(index);
            question.setPositionNo(index + 1);
            questionMapper.updateById(question);
        }
    }

    private void recalculateTotal(Long assessmentId) {
        BigDecimal total = questions(assessmentId).stream()
                .map(AssessmentQuestionEntity::getScore)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        validateTotalScore(total);
        AssessmentEntity assessment = requireAssessment(assessmentId);
        assessment.setTotalScore(total);
        assessmentMapper.updateById(assessment);
    }

    private List<AssessmentQuestionEntity> questions(Long assessmentId) {
        return questionMapper.selectList(new LambdaQueryWrapper<AssessmentQuestionEntity>()
                .eq(AssessmentQuestionEntity::getAssessmentId, assessmentId)
                .orderByAsc(AssessmentQuestionEntity::getPositionNo)
                .orderByAsc(AssessmentQuestionEntity::getId));
    }

    private AssessmentEntity requireVisibleAssessment(AuthenticatedUser principal, Long assessmentId) {
        AssessmentEntity assessment = requireAssessment(assessmentId);
        ClassEntity classroom = classMapper.selectById(assessment.getClassId());
        boolean visible = principal.role() == UserRole.ADMIN
                || (principal.role() == UserRole.TEACHER
                    && classroom != null && courses.role(principal,classroom.getCourseId())!=null)
                || (principal.role() == UserRole.STUDENT
                    && STUDENT_VISIBLE_STATUSES.contains(assessment.getStatus())
                    && classStudentMapper.countMembership(assessment.getClassId(), principal.id()) > 0);
        if (!visible) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "测评不存在");
        }
        courses.requireView(principal,classroom.getCourseId());
        return assessment;
    }

    private AssessmentEntity requireOwnedAssessment(
            AuthenticatedUser principal,
            Long assessmentId,
            boolean lock
    ) {
        AssessmentEntity assessment = lock ? assessmentMapper.selectForUpdate(assessmentId) : assessmentMapper.selectById(assessmentId);
        if (assessment == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "测评不存在");
        }
        ClassEntity classroom = classMapper.selectById(assessment.getClassId());
        if(classroom==null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,"班级不存在");
        if(courses.role(principal,classroom.getCourseId())==null)throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,"测评不存在");
        courses.requireEdit(principal,classroom.getCourseId());
        return assessment;
    }

    private AssessmentEntity requireOwnedAssessmentForUpdate(AuthenticatedUser principal, Long assessmentId) {
        return requireOwnedAssessment(principal, assessmentId, true);
    }

    private AssessmentEntity requireEditableForUpdate(AuthenticatedUser principal, Long assessmentId) {
        AssessmentEntity assessment = requireOwnedAssessmentForUpdate(principal, assessmentId);
        if (assessment.getStatus() != AssessmentStatus.DRAFT && assessment.getStatus() != AssessmentStatus.PARSING) {
            throw conflict("已发布的测评不可直接修改，请先复制为新草稿");
        }
        return assessment;
    }

    private AssessmentEntity requireAssessment(Long assessmentId) {
        AssessmentEntity assessment = assessmentMapper.selectById(assessmentId);
        if (assessment == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "测评不存在");
        }
        return assessment;
    }

    private AssessmentQuestionEntity requireQuestion(Long questionId) {
        AssessmentQuestionEntity question = questionMapper.selectById(questionId);
        if (question == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "题目不存在");
        }
        return question;
    }

    private AssessmentQuestionEntity requireQuestionInAssessment(Long assessmentId, Long questionId) {
        AssessmentQuestionEntity question = requireQuestion(questionId);
        if (!assessmentId.equals(question.getAssessmentId())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "题目不存在");
        }
        return question;
    }

    private ClassEntity requireOwnedClass(AuthenticatedUser principal, Long classId) {
        ClassEntity classroom = classMapper.selectById(classId);
        if (classroom == null || classroom.getStatus() != ClassStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "班级不存在或已归档");
        }
        courses.requireEdit(principal,classroom.getCourseId());
        return classroom;
    }

    private List<Long> teacherClassIds(Long teacherId) {
        return classMapper.selectList(new LambdaQueryWrapper<ClassEntity>()
                        .inSql(ClassEntity::getCourseId,"SELECT course_id FROM edu_course_teacher WHERE teacher_id="+teacherId+" AND status='ACTIVE'"))
                .stream().map(ClassEntity::getId).toList();
    }

    private PublishClaim claimPublish(
            Long userId,
            String operationId,
            String idempotencyKey,
            String requestHash
    ) {
        try {
            PublishClaim result = transactionTemplate.execute(status -> {
                IdempotencyRecordEntity existing = findIdempotency(userId, operationId, idempotencyKey);
                if (existing != null && existing.getExpiresAt().isBefore(LocalDateTime.now())) {
                    idempotencyMapper.deleteById(existing.getId());
                    existing = null;
                }
                if (existing != null) {
                    if (!existing.getRequestHash().equals(requestHash)) {
                        throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT, "同一个幂等键不能用于不同请求");
                    }
                    if (existing.getResponseJson() == null) {
                        throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION, "相同发布请求正在处理中");
                    }
                    return new PublishClaim(existing.getId(), readJson(existing.getResponseJson(), AssessmentDetail.class));
                }
                IdempotencyRecordEntity record = new IdempotencyRecordEntity();
                record.setUserId(userId);
                record.setOperationId(operationId);
                record.setIdempotencyKey(idempotencyKey);
                record.setRequestHash(requestHash);
                record.setExpiresAt(LocalDateTime.now().plusHours(24));
                idempotencyMapper.insert(record);
                return new PublishClaim(record.getId(), null);
            });
            return Objects.requireNonNull(result);
        } catch (DuplicateKeyException exception) {
            IdempotencyRecordEntity existing = findIdempotency(userId, operationId, idempotencyKey);
            if (existing == null || existing.getResponseJson() == null) {
                throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION, "相同发布请求正在处理中");
            }
            return new PublishClaim(existing.getId(), readJson(existing.getResponseJson(), AssessmentDetail.class));
        }
    }

    private IdempotencyRecordEntity findIdempotency(Long userId, String operationId, String key) {
        return idempotencyMapper.selectOne(new LambdaQueryWrapper<IdempotencyRecordEntity>()
                .eq(IdempotencyRecordEntity::getUserId, userId)
                .eq(IdempotencyRecordEntity::getOperationId, operationId)
                .eq(IdempotencyRecordEntity::getIdempotencyKey, key));
    }

    private void releasePublishClaim(Long recordId) {
        transactionTemplate.executeWithoutResult(status -> {
            IdempotencyRecordEntity record = idempotencyMapper.selectById(recordId);
            if (record != null && record.getResponseJson() == null) {
                idempotencyMapper.deleteById(recordId);
            }
        });
    }

    private AssessmentSettings readSettings(String json) {
        if (json == null || json.isBlank()) {
            return AssessmentSettings.defaults();
        }
        return readJson(json, AssessmentSettings.class);
    }

    private <T> T readNullableJson(String json, Class<T> type) {
        return json == null || json.isBlank() ? null : readJson(json, type);
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "测评结构化数据损坏");
        }
    }

    private String writeNullableJson(Object value) {
        return value == null ? null : writeJson(value);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "测评结构化数据序列化失败");
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private <T> void ensureUnique(List<T> values, String message) {
        if (new HashSet<>(values).size() != values.size()) {
            throw validation(message, Map.of());
        }
    }

    private void requireAuthor(AuthenticatedUser principal) {
        if (principal.role() != UserRole.TEACHER) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有任课教师可以管理测评");
        }
    }

    private <T> PageData<T> emptyPage(long page, long size) {
        return new PageData<>(List.of(), page, size, 0, 0);
    }

    private BusinessException validation(String message, Map<String, Object> details) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message, details);
    }

    private BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.INVALID_RESOURCE_STATE, message);
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private LocalDateTime toLocal(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, DATABASE_ZONE);
    }

    private Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(DATABASE_ZONE).toInstant();
    }

    private record PublishClaim(Long recordId, AssessmentDetail replay) {
    }

    private record GraphPublicationMetadata(
            String knowledgePointId,
            String canonicalName,
            String graphVersion,
            Integer contentVersion
    ) {
    }

    private record PublishedKnowledgePointBinding(
            Long questionId,
            String questionClientKey,
            String knowledgePointId,
            String knowledgePointName,
            String graphVersion,
            Integer contentVersion
    ) {
    }

    private record PublicationValidation(
            String draftHash,
            Map<String, String> canonicalNames,
            String graphVersion,
            Map<String, GraphPublicationMetadata> graphMetadata
    ) {
    }
}
