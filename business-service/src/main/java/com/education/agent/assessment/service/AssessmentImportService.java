package com.education.agent.assessment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.education.agent.admin.service.AuditLogService;
import com.education.agent.assessment.dto.AssessmentDtos.ImportTask;
import com.education.agent.assessment.dto.AssessmentDtos.ImportTaskPayload;
import com.education.agent.assessment.entity.AssessmentEntity;
import com.education.agent.assessment.entity.AssessmentImportTaskEntity;
import com.education.agent.assessment.mapper.AssessmentImportTaskMapper;
import com.education.agent.assessment.mapper.AssessmentMapper;
import com.education.agent.assessment.model.AssessmentStatus;
import com.education.agent.assessment.model.TaskStatus;
import com.education.agent.classroom.entity.ClassEntity;
import com.education.agent.classroom.mapper.ClassMapper;
import com.education.agent.common.error.BusinessException;
import com.education.agent.common.error.ErrorCode;
import com.education.agent.file.dto.FileObjectView;
import com.education.agent.file.entity.FileObjectEntity;
import com.education.agent.file.model.FilePurpose;
import com.education.agent.file.service.FileStorageService;
import com.education.agent.security.AuthenticatedUser;
import com.education.agent.user.model.UserRole;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AssessmentImportService {

    private static final ZoneId DATABASE_ZONE = ZoneId.of("Asia/Shanghai");

    private final AssessmentMapper assessmentMapper;
    private final AssessmentImportTaskMapper taskMapper;
    private final ClassMapper classMapper;
    private final FileStorageService fileStorageService;
    private final AssessmentImportProcessor processor;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final com.education.agent.course.CourseAccessService courses;

    public AssessmentImportService(
            AssessmentMapper assessmentMapper,
            AssessmentImportTaskMapper taskMapper,
            ClassMapper classMapper,
            FileStorageService fileStorageService,
            AssessmentImportProcessor processor,
            AuditLogService auditLogService,
            ObjectMapper objectMapper, com.education.agent.course.CourseAccessService courses
    ) {
        this.assessmentMapper = assessmentMapper;
        this.taskMapper = taskMapper;
        this.classMapper = classMapper;
        this.fileStorageService = fileStorageService;
        this.processor = processor;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
        this.courses=courses;
    }

    @Transactional
    public ImportTask importFile(
            AuthenticatedUser principal,
            Long assessmentId,
            MultipartFile file
    ) {
        requireTeacher(principal);
        AssessmentEntity assessment = requireTeacherOwnedForUpdate(principal, assessmentId);
        if (assessment.getStatus() != AssessmentStatus.DRAFT
                && assessment.getStatus() != AssessmentStatus.PARSING) {
            throw new BusinessException(ErrorCode.INVALID_RESOURCE_STATE, "已发布的测评不能导入题目文件");
        }

        if (hasActiveImport(assessmentId)) {
            throw new BusinessException(ErrorCode.INVALID_RESOURCE_STATE, "已有题目文件正在解析，请等待完成后再导入");
        }

        FileObjectView uploaded = fileStorageService.store(principal, file, FilePurpose.ASSESSMENT_SOURCE);
        FileObjectEntity uploadedEntity = fileStorageService.findAvailable(uploaded.id());
        String dedupeHash = sha256(assessmentId + ":" + uploadedEntity.getSha256());
        AssessmentImportTaskEntity existing = taskMapper.selectByAssessmentAndHashForUpdate(assessmentId, dedupeHash);

        if (existing != null) {
            fileStorageService.delete(principal, uploaded.id());
            if (existing.getStatus() == TaskStatus.FAILED) {
                resetForRetry(existing);
                assessment.setStatus(AssessmentStatus.PARSING);
                assessmentMapper.updateById(assessment);
                schedule(existing.getId());
            }
            return toView(existing.getId());
        }

        AssessmentImportTaskEntity task = new AssessmentImportTaskEntity();
        task.setAssessmentId(assessmentId);
        task.setSourceFileId(uploaded.id());
        task.setRequestId(UUID.randomUUID().toString());
        task.setDedupeHash(dedupeHash);
        task.setStatus(TaskStatus.PENDING);
        task.setProgressPercent(0);
        task.setAttemptCount(0);
        taskMapper.insert(task);

        assessment.setStatus(AssessmentStatus.PARSING);
        assessmentMapper.updateById(assessment);
        schedule(task.getId());
        auditLogService.record(
                "ASSESSMENT_IMPORT_CREATED", "ASSESSMENT_IMPORT_TASK", task.getId().toString(), principal.id(), true,
                Map.of("assessmentId", assessmentId, "sourceFileId", uploaded.id())
        );
        return toView(task.getId());
    }

    public ImportTask getTask(AuthenticatedUser principal, Long assessmentId, Long taskId) {
        requireTeacherOrAdmin(principal);
        requireReadable(principal, assessmentId);
        AssessmentImportTaskEntity task = taskMapper.selectById(taskId);
        if (task == null || !assessmentId.equals(task.getAssessmentId())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "解析任务不存在");
        }
        return toView(taskId);
    }

    private void resetForRetry(AssessmentImportTaskEntity task) {
        task.setRequestId(UUID.randomUUID().toString());
        task.setStatus(TaskStatus.PENDING);
        task.setProgressPercent(0);
        task.setResultJson(null);
        task.setErrorCode(null);
        task.setErrorMessage(null);
        task.setModelVersion(null);
        task.setPromptVersion(null);
        task.setStartedAt(null);
        task.setFinishedAt(null);
        taskMapper.updateById(task);
    }

    private ImportTask toView(Long taskId) {
        AssessmentImportTaskEntity task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "解析任务不存在");
        }
        FileObjectEntity source = fileStorageService.findAvailable(task.getSourceFileId());
        ImportTaskPayload payload = task.getResultJson() == null || task.getResultJson().isBlank()
                ? new ImportTaskPayload(null, List.of(), List.of())
                : readPayload(task.getResultJson());
        return new ImportTask(
                task.getId(), task.getAssessmentId(), FileObjectView.from(source), task.getStatus(),
                task.getProgressPercent(), payload.titleSuggestion(), payload.candidateQuestions(), payload.warnings(),
                task.getErrorCode(), task.getErrorMessage(), task.getModelVersion(), task.getPromptVersion(),
                toInstant(task.getCreatedAt()), toInstant(task.getFinishedAt())
        );
    }

    private void schedule(Long taskId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    processor.processAsync(taskId);
                }
            });
        } else {
            processor.processAsync(taskId);
        }
    }

    private boolean hasActiveImport(Long assessmentId) {
        return taskMapper.selectCount(new LambdaQueryWrapper<AssessmentImportTaskEntity>()
                .eq(AssessmentImportTaskEntity::getAssessmentId, assessmentId)
                .in(AssessmentImportTaskEntity::getStatus, TaskStatus.PENDING, TaskStatus.RUNNING)) > 0;
    }

    private AssessmentEntity requireTeacherOwnedForUpdate(AuthenticatedUser principal, Long assessmentId) {
        AssessmentEntity assessment = assessmentMapper.selectForUpdate(assessmentId);
        verifyTeacherOwner(principal, assessment);
        return assessment;
    }

    private AssessmentEntity requireReadable(AuthenticatedUser principal, Long assessmentId) {
        AssessmentEntity assessment = assessmentMapper.selectById(assessmentId);
        if (principal.role() != UserRole.ADMIN) {
            verifyTeacherOwner(principal, assessment);
        } else if (assessment == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "测评不存在");
        }
        return assessment;
    }

    private void verifyTeacherOwner(AuthenticatedUser principal, AssessmentEntity assessment) {
        if (assessment == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "测评不存在");
        }
        ClassEntity classroom = classMapper.selectById(assessment.getClassId());
        if(classroom==null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,"班级不存在");
        courses.requireEdit(principal,classroom.getCourseId());
    }

    private void requireTeacher(AuthenticatedUser principal) {
        if (principal.role() != UserRole.TEACHER) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有任课教师可以导入题目");
        }
    }

    private void requireTeacherOrAdmin(AuthenticatedUser principal) {
        if (principal.role() != UserRole.TEACHER && principal.role() != UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有教师或管理员可以查看解析任务");
        }
    }

    private ImportTaskPayload readPayload(String json) {
        try {
            return objectMapper.readValue(json, ImportTaskPayload.class);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "解析任务结果数据损坏");
        }
    }

    private String sha256(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(DATABASE_ZONE).toInstant();
    }
}
