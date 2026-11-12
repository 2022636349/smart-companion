package com.education.agent.assessment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.education.agent.assessment.dto.AssessmentDtos.AnswerContent;
import com.education.agent.assessment.dto.AssessmentDtos.AttachmentPolicy;
import com.education.agent.assessment.dto.AssessmentDtos.ImportCandidateQuestion;
import com.education.agent.assessment.dto.AssessmentDtos.ImportTaskPayload;
import com.education.agent.assessment.dto.AssessmentDtos.KnowledgePointBinding;
import com.education.agent.assessment.dto.AssessmentDtos.ProgrammingConfig;
import com.education.agent.assessment.dto.AssessmentDtos.ProgrammingTestCaseDraft;
import com.education.agent.assessment.dto.AssessmentDtos.QuestionOptionDraft;
import com.education.agent.assessment.dto.AssessmentDtos.RubricItem;
import com.education.agent.assessment.entity.AssessmentEntity;
import com.education.agent.assessment.entity.AssessmentImportTaskEntity;
import com.education.agent.assessment.mapper.AssessmentImportTaskMapper;
import com.education.agent.assessment.mapper.AssessmentMapper;
import com.education.agent.assessment.model.AssessmentStatus;
import com.education.agent.assessment.model.CompareMode;
import com.education.agent.assessment.model.QuestionType;
import com.education.agent.assessment.model.TaskStatus;
import com.education.agent.classroom.entity.ClassEntity;
import com.education.agent.classroom.mapper.ClassMapper;
import com.education.agent.common.error.BusinessException;
import com.education.agent.common.error.ErrorCode;
import com.education.agent.file.entity.FileObjectEntity;
import com.education.agent.file.service.FileStorageService;
import com.education.agent.integration.ai.AiAssessmentParseRequest;
import com.education.agent.integration.ai.AiAssessmentParseResponse;
import com.education.agent.integration.ai.AiServiceClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Component
public class AssessmentImportProcessor {

    private static final Logger log = LoggerFactory.getLogger(AssessmentImportProcessor.class);
    private static final int MAX_QUESTION_COUNT = 500;
    private static final int MAX_STEM_LENGTH = 30_000;
    private static final BigDecimal MAX_SCORE = new BigDecimal("999999.99");

    private final TaskExecutor executor;
    private final AssessmentImportTaskMapper taskMapper;
    private final AssessmentMapper assessmentMapper;
    private final ClassMapper classMapper;
    private final FileStorageService fileStorageService;
    private final AssessmentFileDownloadUrlFactory downloadUrlFactory;
    private final AiServiceClient aiServiceClient;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public AssessmentImportProcessor(
            @Qualifier("assessmentImportExecutor") TaskExecutor executor,
            AssessmentImportTaskMapper taskMapper,
            AssessmentMapper assessmentMapper,
            ClassMapper classMapper,
            FileStorageService fileStorageService,
            AssessmentFileDownloadUrlFactory downloadUrlFactory,
            AiServiceClient aiServiceClient,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager
    ) {
        this.executor = executor;
        this.taskMapper = taskMapper;
        this.assessmentMapper = assessmentMapper;
        this.classMapper = classMapper;
        this.fileStorageService = fileStorageService;
        this.downloadUrlFactory = downloadUrlFactory;
        this.aiServiceClient = aiServiceClient;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public void processAsync(Long taskId) {
        try {
            executor.execute(() -> process(taskId));
        } catch (RuntimeException exception) {
            log.warn("Assessment import queue rejected task {}", taskId, exception);
            fail(taskId, ErrorCode.AI_SERVICE_UNAVAILABLE.name(), "AI 解析队列暂时繁忙，请重新上传文件重试");
        }
    }

    void process(Long taskId) {
        ImportContext context;
        try {
            context = transactionTemplate.execute(status -> start(taskId));
        } catch (BusinessException exception) {
            fail(taskId, exception.errorCode().name(), safeErrorMessage(exception.getMessage()));
            return;
        } catch (RuntimeException exception) {
            log.warn("Assessment import task {} could not start", taskId, exception);
            fail(taskId, ErrorCode.INTERNAL_ERROR.name(), "AI 题目解析任务无法启动，请重新上传文件重试");
            return;
        }
        if (context == null) {
            return;
        }
        try {
            AiAssessmentParseResponse response = aiServiceClient.parseAssessment(new AiAssessmentParseRequest(
                    context.requestId(),
                    context.fileId(),
                    context.filename(),
                    downloadUrlFactory.forFile(context.fileId()),
                    context.courseId(),
                    "zh-CN"
            ));
            ImportTaskPayload payload = validateAndMap(context, response);
            transactionTemplate.executeWithoutResult(status -> succeed(taskId, response, payload));
        } catch (BusinessException exception) {
            fail(taskId, exception.errorCode().name(), safeErrorMessage(exception.getMessage()));
        } catch (RuntimeException exception) {
            log.warn("Assessment import task {} failed", taskId, exception);
            fail(taskId, ErrorCode.AI_SERVICE_UNAVAILABLE.name(), "AI 题目解析任务执行失败，请稍后重试");
        }
    }

    private ImportContext start(Long taskId) {
        AssessmentImportTaskEntity task = taskMapper.selectForUpdate(taskId);
        if (task == null || task.getStatus() != TaskStatus.PENDING) {
            return null;
        }
        AssessmentEntity assessment = assessmentMapper.selectById(task.getAssessmentId());
        if (assessment == null
                || (assessment.getStatus() != AssessmentStatus.DRAFT
                    && assessment.getStatus() != AssessmentStatus.PARSING)) {
            task.setStatus(TaskStatus.FAILED);
            task.setErrorCode(ErrorCode.INVALID_RESOURCE_STATE.name());
            task.setErrorMessage("测评状态已改变，解析任务已取消");
            task.setFinishedAt(LocalDateTime.now());
            taskMapper.updateById(task);
            return null;
        }
        FileObjectEntity file = fileStorageService.findAvailable(task.getSourceFileId());
        ClassEntity classroom = classMapper.selectById(assessment.getClassId());
        if (classroom == null || classroom.getCourseId() == null) {
            task.setStatus(TaskStatus.FAILED);
            task.setErrorCode(ErrorCode.RESOURCE_NOT_FOUND.name());
            task.setErrorMessage("测评所属课程不存在");
            task.setFinishedAt(LocalDateTime.now());
            taskMapper.updateById(task);
            restoreDraftIfFinished(task.getAssessmentId());
            return null;
        }

        task.setStatus(TaskStatus.RUNNING);
        task.setProgressPercent(10);
        task.setAttemptCount((task.getAttemptCount() == null ? 0 : task.getAttemptCount()) + 1);
        task.setStartedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        return new ImportContext(
                task.getId(), task.getAssessmentId(), task.getRequestId(), file.getId(), file.getOriginalName(),
                classroom.getCourseId()
        );
    }

    private ImportTaskPayload validateAndMap(
            ImportContext context,
            AiAssessmentParseResponse response
    ) {
        if (response == null
                || response.requestId() == null
                || !response.requestId().equals(context.requestId())
                || response.modelVersion() == null
                || response.modelVersion().isBlank()
                || response.promptVersion() == null
                || response.promptVersion().isBlank()
                || response.questions() == null
                || response.warnings() == null) {
            throw invalidAiResponse("解析响应缺少必需字段或 request_id 不匹配");
        }
        if (response.questions().size() > MAX_QUESTION_COUNT) {
            throw invalidAiResponse("解析出的题目数量超过上限");
        }

        List<String> taskWarnings = sanitizeWarnings(response.warnings());
        List<ImportCandidateQuestion> candidates = new ArrayList<>();
        Set<String> clientKeys = new HashSet<>();
        for (int index = 0; index < response.questions().size(); index++) {
            AiAssessmentParseResponse.QuestionDraft source = response.questions().get(index);
            candidates.add(mapQuestion(source, index + 1, clientKeys));
        }
        return new ImportTaskPayload(trimToNull(response.titleSuggestion()), candidates, taskWarnings);
    }

    private ImportCandidateQuestion mapQuestion(
            AiAssessmentParseResponse.QuestionDraft source,
            int position,
            Set<String> clientKeys
    ) {
        if (source == null
                || source.clientKey() == null
                || source.clientKey().isBlank()
                || source.clientKey().length() > 64
                || source.questionType() == null
                || source.stem() == null
                || source.stem().isBlank()
                || source.stem().length() > MAX_STEM_LENGTH
                || !isValidPositiveScore(source.score())
                || source.confidence() == null) {
            throw invalidAiResponse("候选题目字段不完整");
        }
        String clientKey = source.clientKey().trim();
        if (!clientKeys.add(clientKey)) {
            throw invalidAiResponse("候选题目 client_key 重复");
        }
        String confidence = source.confidence().trim().toUpperCase();
        if (!Set.of("HIGH", "MEDIUM", "LOW").contains(confidence)) {
            throw invalidAiResponse("候选题目信心等级不合法");
        }

        List<String> warnings = new ArrayList<>(sanitizeWarnings(source.warnings()));
        List<QuestionOptionDraft> options = mapOptions(source.options());
        List<RubricItem> rubrics = mapRubrics(clientKey, source.rubricItems());
        List<ProgrammingTestCaseDraft> tests = mapTests(clientKey, source.testCases());
        List<KnowledgePointBinding> knowledgePoints = mapKnowledgePoints(source.knowledgePointCandidates(), warnings);
        AnswerContent referenceAnswer = mapReferenceAnswer(source.questionType(), source.referenceAnswer(), options);

        ProgrammingConfig programmingConfig = null;
        AttachmentPolicy attachmentPolicy = null;
        if (source.questionType() == QuestionType.PROGRAMMING) {
            programmingConfig = new ProgrammingConfig(
                    "PYTHON", "", 2000, 128, CompareMode.TRIM_TRAILING_WHITESPACE, null
            );
            warnings.add("AI 解析合同不含完整运行配置，已注入 PYTHON/2秒/128MB/忽略行尾空白的安全默认值，请教师确认");
        } else if (source.questionType() == QuestionType.ATTACHMENT) {
            attachmentPolicy = new AttachmentPolicy(List.of(".pdf", ".docx", ".zip"), 1, 20);
            warnings.add("AI 解析合同不含附件规则，已注入 PDF/DOCX/ZIP、最多1份、20MB 的安全默认值，请教师确认");
        }

        return new ImportCandidateQuestion(
                null,
                clientKey,
                source.questionType(),
                source.stem().trim(),
                source.score(),
                position,
                options,
                referenceAnswer,
                rubrics,
                programmingConfig,
                tests,
                attachmentPolicy,
                knowledgePoints,
                true,
                confidence,
                confidenceScore(confidence),
                List.copyOf(warnings)
        );
    }

    private List<QuestionOptionDraft> mapOptions(List<AiAssessmentParseResponse.QuestionOptionDraft> source) {
        if (source == null) {
            return List.of();
        }
        List<QuestionOptionDraft> result = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (int index = 0; index < source.size(); index++) {
            AiAssessmentParseResponse.QuestionOptionDraft option = source.get(index);
            if (option == null
                    || option.key() == null
                    || option.key().isBlank()
                    || option.key().length() > 20
                    || option.content() == null
                    || option.content().isBlank()
                    || option.isCorrect() == null
                    || !keys.add(option.key().trim())) {
                throw invalidAiResponse("候选题目的选项不合法");
            }
            result.add(new QuestionOptionDraft(
                    null, option.key().trim(), option.content().trim(), option.isCorrect(), index + 1
            ));
        }
        return List.copyOf(result);
    }

    private List<RubricItem> mapRubrics(
            String questionClientKey,
            List<AiAssessmentParseResponse.RubricItem> source
    ) {
        if (source == null) {
            return List.of();
        }
        List<RubricItem> result = new ArrayList<>();
        for (int index = 0; index < source.size(); index++) {
            AiAssessmentParseResponse.RubricItem rubric = source.get(index);
            if (rubric == null
                    || rubric.label() == null
                    || rubric.label().isBlank()
                    || rubric.description() == null
                    || rubric.description().isBlank()
                    || !isValidPositiveScore(rubric.maxScore())) {
                throw invalidAiResponse("候选题目的评分点不合法");
            }
            result.add(new RubricItem(
                    null,
                    childClientKey(questionClientKey, "rubric", index + 1),
                    rubric.label().trim(),
                    rubric.description().trim(),
                    rubric.maxScore(),
                    index + 1
            ));
        }
        return List.copyOf(result);
    }

    private List<ProgrammingTestCaseDraft> mapTests(
            String questionClientKey,
            List<AiAssessmentParseResponse.TestCaseDraft> source
    ) {
        if (source == null) {
            return List.of();
        }
        List<ProgrammingTestCaseDraft> result = new ArrayList<>();
        for (int index = 0; index < source.size(); index++) {
            AiAssessmentParseResponse.TestCaseDraft test = source.get(index);
            if (test == null
                    || test.inputData() == null
                    || test.expectedOutput() == null
                    || !isValidPositiveScore(test.score())
                    || test.visibility() == null
                    || test.compareMode() == null) {
                throw invalidAiResponse("候选编程题的测试点不合法");
            }
            result.add(new ProgrammingTestCaseDraft(
                    null,
                    childClientKey(questionClientKey, "test", index + 1),
                    test.inputData(),
                    test.expectedOutput(),
                    test.score(),
                    test.visibility(),
                    index + 1
            ));
        }
        return List.copyOf(result);
    }

    private List<KnowledgePointBinding> mapKnowledgePoints(
            List<AiAssessmentParseResponse.KnowledgePointCandidate> source,
            List<String> warnings
    ) {
        if (source == null) {
            return List.of();
        }
        List<KnowledgePointBinding> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (AiAssessmentParseResponse.KnowledgePointCandidate candidate : source) {
            if (candidate == null
                    || candidate.knowledgePointId() == null
                    || candidate.knowledgePointId().isBlank()
                    || candidate.knowledgePointId().length() > 64
                    || candidate.name() == null
                    || candidate.name().isBlank()
                    || candidate.score() == null
                    || candidate.score().compareTo(BigDecimal.ZERO) < 0
                    || candidate.score().compareTo(BigDecimal.ONE) > 0) {
                throw invalidAiResponse("候选知识点不合法");
            }
            if (ids.add(candidate.knowledgePointId().trim())) {
                result.add(new KnowledgePointBinding(candidate.knowledgePointId().trim(), candidate.name().trim()));
            }
            if (candidate.score().compareTo(new BigDecimal("0.75")) < 0) {
                warnings.add("知识点“" + candidate.name().trim() + "”匹配置信度较低，请教师确认");
            }
        }
        return List.copyOf(result);
    }

    private AnswerContent mapReferenceAnswer(
            QuestionType questionType,
            JsonNode source,
            List<QuestionOptionDraft> options
    ) {
        List<String> selected = options.stream()
                .filter(option -> Boolean.TRUE.equals(option.isCorrect()))
                .map(QuestionOptionDraft::key)
                .toList();
        if (source == null || source.isNull()) {
            return selected.isEmpty() ? null : new AnswerContent(selected, null, null, List.of());
        }
        if (source.isTextual()) {
            return questionType == QuestionType.PROGRAMMING
                    ? new AnswerContent(selected, null, source.asText(), List.of())
                    : new AnswerContent(selected, source.asText(), null, List.of());
        }
        if (!source.isObject()) {
            throw invalidAiResponse("候选题目的参考答案格式不合法");
        }
        List<String> selectedFromJson = stringArray(first(source, "selectedOptionKeys", "selected_option_keys"));
        if (!selectedFromJson.isEmpty()) {
            selected = selectedFromJson;
        }
        return new AnswerContent(
                selected,
                text(first(source, "textAnswer", "text_answer")),
                text(first(source, "sourceCode", "source_code")),
                longArray(first(source, "attachmentFileIds", "attachment_file_ids"))
        );
    }

    private void succeed(
            Long taskId,
            AiAssessmentParseResponse response,
            ImportTaskPayload payload
    ) {
        AssessmentImportTaskEntity task = taskMapper.selectForUpdate(taskId);
        if (task == null || task.getStatus() != TaskStatus.RUNNING) {
            return;
        }
        task.setStatus(TaskStatus.SUCCEEDED);
        task.setProgressPercent(100);
        task.setResultJson(writeJson(payload));
        task.setErrorCode(null);
        task.setErrorMessage(null);
        task.setModelVersion(response.modelVersion().trim());
        task.setPromptVersion(response.promptVersion().trim());
        task.setFinishedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        restoreDraftIfFinished(task.getAssessmentId());
    }

    private void fail(Long taskId, String errorCode, String errorMessage) {
        transactionTemplate.executeWithoutResult(status -> {
            AssessmentImportTaskEntity task = taskMapper.selectForUpdate(taskId);
            if (task == null || (task.getStatus() != TaskStatus.RUNNING && task.getStatus() != TaskStatus.PENDING)) {
                return;
            }
            task.setStatus(TaskStatus.FAILED);
            task.setProgressPercent(Math.max(task.getProgressPercent() == null ? 0 : task.getProgressPercent(), 10));
            task.setResultJson(null);
            task.setErrorCode(errorCode);
            task.setErrorMessage(errorMessage);
            task.setFinishedAt(LocalDateTime.now());
            taskMapper.updateById(task);
            restoreDraftIfFinished(task.getAssessmentId());
        });
    }

    private void restoreDraftIfFinished(Long assessmentId) {
        long active = taskMapper.selectCount(new LambdaQueryWrapper<AssessmentImportTaskEntity>()
                .eq(AssessmentImportTaskEntity::getAssessmentId, assessmentId)
                .in(AssessmentImportTaskEntity::getStatus, TaskStatus.PENDING, TaskStatus.RUNNING));
        if (active > 0) {
            return;
        }
        AssessmentEntity assessment = assessmentMapper.selectForUpdate(assessmentId);
        if (assessment != null && assessment.getStatus() == AssessmentStatus.PARSING) {
            assessment.setStatus(AssessmentStatus.DRAFT);
            assessmentMapper.updateById(assessment);
        }
    }

    private JsonNode first(JsonNode object, String... names) {
        for (String name : names) {
            JsonNode value = object.get(name);
            if (value != null && !value.isNull()) {
                return value;
            }
        }
        return null;
    }

    private String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private List<String> stringArray(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw invalidAiResponse("参考答案选项格式不合法");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode value : node) {
            if (!value.isTextual() || value.asText().isBlank()) {
                throw invalidAiResponse("参考答案选项格式不合法");
            }
            values.add(value.asText());
        }
        return List.copyOf(values);
    }

    private List<Long> longArray(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw invalidAiResponse("参考答案附件格式不合法");
        }
        List<Long> values = new ArrayList<>();
        for (JsonNode value : node) {
            if (!value.canConvertToLong()) {
                throw invalidAiResponse("参考答案附件格式不合法");
            }
            values.add(value.asLong());
        }
        return List.copyOf(values);
    }

    private List<String> sanitizeWarnings(List<String> warnings) {
        if (warnings == null) {
            return List.of();
        }
        return warnings.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .limit(100)
                .map(value -> value.length() > 500 ? value.substring(0, 500) : value)
                .toList();
    }

    private BigDecimal confidenceScore(String confidence) {
        return switch (confidence) {
            case "HIGH" -> new BigDecimal("0.90");
            case "MEDIUM" -> new BigDecimal("0.65");
            default -> new BigDecimal("0.35");
        };
    }

    private boolean isValidPositiveScore(BigDecimal value) {
        return value != null
                && value.signum() > 0
                && value.compareTo(MAX_SCORE) <= 0
                && value.scale() <= 2;
    }

    private String childClientKey(String parent, String kind, int position) {
        String suffix = "-" + kind + "-" + position;
        int prefixLength = Math.min(parent.length(), 64 - suffix.length());
        return parent.substring(0, prefixLength) + suffix;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "无法保存 AI 解析结果");
        }
    }

    private String safeErrorMessage(String value) {
        if (value == null || value.isBlank()) {
            return "AI 题目解析失败，请稍后重试";
        }
        String sanitized = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return sanitized.length() > 300 ? sanitized.substring(0, 300) : sanitized;
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private BusinessException invalidAiResponse(String reason) {
        return new BusinessException(
                ErrorCode.AI_SERVICE_INVALID_RESPONSE,
                "AI 题目解析结果未通过业务校验",
                Map.of("reason", reason)
        );
    }

    private record ImportContext(
            Long taskId,
            Long assessmentId,
            String requestId,
            Long fileId,
            String filename,
            Long courseId
    ) {
    }
}
