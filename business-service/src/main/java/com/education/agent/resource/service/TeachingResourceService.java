package com.education.agent.resource.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.education.agent.admin.service.AuditLogService;
import com.education.agent.common.api.PageData;
import com.education.agent.common.error.BusinessException;
import com.education.agent.common.error.ErrorCode;
import com.education.agent.file.dto.FileObjectView;
import com.education.agent.file.entity.FileObjectEntity;
import com.education.agent.file.model.FilePurpose;
import com.education.agent.file.service.FileStorageService;
import com.education.agent.integration.knowledge.KnowledgeServiceClient;
import com.education.agent.integration.knowledge.ApprovedGraphContextValidator;
import com.education.agent.resource.dto.ResourceReviewRequest;
import com.education.agent.resource.dto.ResourceUpsertRequest;
import com.education.agent.resource.dto.TeachingResourceView;
import com.education.agent.resource.entity.TeachingResourceEntity;
import com.education.agent.resource.mapper.ResourceKnowledgePointMapper;
import com.education.agent.resource.mapper.TeachingResourceMapper;
import com.education.agent.resource.model.Difficulty;
import com.education.agent.resource.model.ResourceType;
import com.education.agent.resource.model.ReviewStatus;
import com.education.agent.security.AuthenticatedUser;
import com.education.agent.user.dto.UserView;
import com.education.agent.user.entity.UserEntity;
import com.education.agent.user.mapper.UserMapper;
import com.education.agent.user.model.UserRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class TeachingResourceService {

    private static final ZoneId DATABASE_ZONE = ZoneId.of("Asia/Shanghai");

    private final TeachingResourceMapper resourceMapper;
    private final ResourceKnowledgePointMapper bindingMapper;
    private final FileStorageService fileStorageService;
    private final UserMapper userMapper;
    private final KnowledgeServiceClient knowledgeServiceClient;
    private final AuditLogService auditLogService;
    private final TransactionTemplate transactionTemplate;
    private final com.education.agent.course.CourseAccessService courses;

    public TeachingResourceService(
            TeachingResourceMapper resourceMapper,
            ResourceKnowledgePointMapper bindingMapper,
            FileStorageService fileStorageService,
            UserMapper userMapper,
            KnowledgeServiceClient knowledgeServiceClient,
            AuditLogService auditLogService,
            PlatformTransactionManager transactionManager, com.education.agent.course.CourseAccessService courses
    ) {
        this.courses = courses;
        this.resourceMapper = resourceMapper;
        this.bindingMapper = bindingMapper;
        this.fileStorageService = fileStorageService;
        this.userMapper = userMapper;
        this.knowledgeServiceClient = knowledgeServiceClient;
        this.auditLogService = auditLogService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public PageData<TeachingResourceView> list(
            AuthenticatedUser principal, Long courseId,
            long page,
            long size,
            String knowledgePointId,
            ResourceType resourceType,
            ReviewStatus requestedStatus
    ) {
        courses.requireView(principal,courseId);
        LambdaQueryWrapper<TeachingResourceEntity> query = new LambdaQueryWrapper<TeachingResourceEntity>()
                .eq(TeachingResourceEntity::getCourseId,courseId)
                .eq(resourceType != null, TeachingResourceEntity::getResourceType, resourceType)
                .orderByDesc(TeachingResourceEntity::getCreatedAt);

        if (principal.role() == UserRole.STUDENT) {
            query.eq(TeachingResourceEntity::getReviewStatus, ReviewStatus.APPROVED);
        } else {
            query.eq(requestedStatus != null, TeachingResourceEntity::getReviewStatus, requestedStatus);
        }

        if (knowledgePointId != null && !knowledgePointId.isBlank()) {
            List<Long> resourceIds = bindingMapper.findResourceIds(knowledgePointId);
            if (resourceIds.isEmpty()) {
                return new PageData<>(List.of(), page, size, 0, 0);
            }
            query.in(TeachingResourceEntity::getId, resourceIds);
        }

        Page<TeachingResourceEntity> result = resourceMapper.selectPage(Page.of(page, size), query);
        List<TeachingResourceView> items = result.getRecords().stream().map(this::toView).toList();
        return new PageData<>(items, result.getCurrent(), result.getSize(), result.getTotal(), result.getPages());
    }

    public TeachingResourceView get(AuthenticatedUser principal, Long resourceId) {
        TeachingResourceEntity entity = requireEntity(resourceId);
        requireVisible(principal, entity);
        return toView(entity);
    }

    @Transactional
    public TeachingResourceView create(AuthenticatedUser principal, ResourceUpsertRequest request) {
        courses.requireEdit(principal,request.courseId());
        requireAuthor(principal);
        validateSource(principal, request, null);
        TeachingResourceEntity entity = new TeachingResourceEntity();
        apply(entity, request);
        entity.setUploaderId(principal.id());
        entity.setReviewStatus(ReviewStatus.PENDING);
        resourceMapper.insert(entity);
        replaceBindings(entity.getId(), request.knowledgePointIds());
        auditLogService.record(
                "RESOURCE_CREATED", "TEACHING_RESOURCE", entity.getId().toString(),
                principal.id(), true, Map.of("title", entity.getTitle())
        );
        return toView(requireEntity(entity.getId()));
    }

    @Transactional
    public TeachingResourceView update(
            AuthenticatedUser principal,
            Long resourceId,
            ResourceUpsertRequest request
    ) {
        TeachingResourceEntity entity = requireEntity(resourceId);
        requireOwnerOrAdmin(principal, entity);
        if(!Objects.equals(entity.getCourseId(),request.courseId())) throw new BusinessException(ErrorCode.VALIDATION_ERROR,"资源不能移动到其他课程");
        if (entity.getReviewStatus() == ReviewStatus.ARCHIVED) {
            throw new BusinessException(ErrorCode.INVALID_RESOURCE_STATE, "已归档资源不能修改");
        }
        validateSource(principal, request, entity.getFileId());
        apply(entity, request);
        entity.setReviewStatus(ReviewStatus.PENDING);
        entity.setReviewComment(null);
        resourceMapper.updateById(entity);
        replaceBindings(entity.getId(), request.knowledgePointIds());
        auditLogService.record(
                "RESOURCE_UPDATED", "TEACHING_RESOURCE", entity.getId().toString(),
                principal.id(), true, Map.of("reviewStatus", ReviewStatus.PENDING.name())
        );
        return toView(requireEntity(entity.getId()));
    }

    @Transactional
    public void archive(AuthenticatedUser principal, Long resourceId) {
        TeachingResourceEntity entity = requireEntity(resourceId);
        requireOwnerOrAdmin(principal, entity);
        entity.setReviewStatus(ReviewStatus.ARCHIVED);
        resourceMapper.updateById(entity);
        auditLogService.record(
                "RESOURCE_ARCHIVED", "TEACHING_RESOURCE", entity.getId().toString(),
                principal.id(), true, Map.of()
        );
    }

    public TeachingResourceView review(
            AuthenticatedUser principal,
            Long resourceId,
            ResourceReviewRequest request
    ) {
        courses.requireEdit(principal,requireEntity(resourceId).getCourseId());
        if (request.reviewStatus() != ReviewStatus.APPROVED
                && request.reviewStatus() != ReviewStatus.REJECTED) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "审核状态只能是 APPROVED 或 REJECTED");
        }
        TeachingResourceEntity entity = requireEntity(resourceId);
        if (entity.getReviewStatus() == ReviewStatus.ARCHIVED) {
            throw new BusinessException(ErrorCode.INVALID_RESOURCE_STATE, "已归档资源不能审核");
        }
        ReviewSnapshot reviewSnapshot = null;
        if (request.reviewStatus() == ReviewStatus.APPROVED) {
            List<String> knowledgePointIds = bindingMapper.findKnowledgePointIds(resourceId);
            reviewSnapshot = ReviewSnapshot.from(entity, knowledgePointIds);
            for (String knowledgePointId : knowledgePointIds) {
                ApprovedGraphContextValidator.require(knowledgePointId,
                        knowledgeServiceClient.ragContext(entity.getCourseId(),knowledgePointId));
            }
        }
        ReviewSnapshot validatedSnapshot = reviewSnapshot;
        TeachingResourceEntity reviewed = transactionTemplate.execute(status -> {
            TeachingResourceEntity latest = resourceMapper.selectByIdForUpdate(resourceId);
            if (latest == null) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "教学资源不存在");
            }
            if (latest.getReviewStatus() == ReviewStatus.ARCHIVED) {
                throw new BusinessException(ErrorCode.INVALID_RESOURCE_STATE, "已归档资源不能审核");
            }
            if (validatedSnapshot != null && !validatedSnapshot.matches(
                    latest,
                    bindingMapper.findKnowledgePointIds(resourceId)
            )) {
                throw new BusinessException(
                        ErrorCode.CONCURRENT_MODIFICATION,
                        "教学资源在图谱校验期间已被修改，请重新审核"
                );
            }
            latest.setReviewStatus(request.reviewStatus());
            latest.setReviewComment(request.comment());
            resourceMapper.updateById(latest);
            Map<String, Object> details = new HashMap<>();
            details.put("reviewStatus", request.reviewStatus().name());
            if (request.comment() != null) {
                details.put("comment", request.comment());
            }
            auditLogService.record(
                    "RESOURCE_REVIEWED", "TEACHING_RESOURCE", latest.getId().toString(),
                    principal.id(), true, details
            );
            return resourceMapper.selectById(resourceId);
        });
        if (reviewed == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "审核教学资源失败");
        }
        return toView(reviewed);
    }

    private void apply(TeachingResourceEntity entity, ResourceUpsertRequest request) {
        entity.setCourseId(request.courseId());
        entity.setTitle(request.title().trim());
        entity.setDescription(trimToNull(request.description()));
        entity.setResourceType(request.resourceType());
        entity.setFileId(request.fileId());
        entity.setExternalUrl(trimToNull(request.externalUrl()));
        entity.setDifficulty(request.difficulty());
    }

    private void validateSource(AuthenticatedUser principal, ResourceUpsertRequest request, Long retainedFileId) {
        boolean hasFile = request.fileId() != null;
        boolean hasUrl = request.externalUrl() != null && !request.externalUrl().isBlank();
        if (hasFile == hasUrl) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "教学资源必须且只能提供文件或外部链接之一");
        }
        if (request.resourceType() == ResourceType.EXTERNAL_LINK && !hasUrl) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "外部链接资源必须提供 externalUrl");
        }
        if (request.resourceType() != ResourceType.EXTERNAL_LINK && !hasFile) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "此资源类型必须提供 fileId");
        }
        if (hasFile) {
            // Course editors may retain this resource's existing file when editing
            // metadata. New/replacement files must still belong to the caller.
            if (Objects.equals(request.fileId(), retainedFileId)) {
                FileObjectEntity existing = fileStorageService.findAvailable(retainedFileId);
                if (existing.getPurpose() != FilePurpose.TEACHING_RESOURCE) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR, "文件用途不匹配");
                }
            } else {
                fileStorageService.requireOwnedAvailable(request.fileId(), principal, FilePurpose.TEACHING_RESOURCE);
            }
        }
        if (hasUrl) {
            validateHttpUrl(request.externalUrl());
        }
    }

    private void validateHttpUrl(String value) {
        try {
            URI uri = URI.create(value.trim());
            if (uri.getHost() == null || !("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalArgumentException("not an HTTP URL");
            }
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "externalUrl 必须是有效的 HTTP/HTTPS 地址");
        }
    }

    private void replaceBindings(Long resourceId, List<String> knowledgePointIds) {
        bindingMapper.deleteByResourceId(resourceId);
        knowledgePointIds.stream()
                .map(String::trim)
                .distinct()
                .forEach(id -> bindingMapper.insertBinding(resourceId, id));
    }

    private TeachingResourceEntity requireEntity(Long resourceId) {
        TeachingResourceEntity entity = resourceMapper.selectById(resourceId);
        if (entity == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "教学资源不存在");
        }
        return entity;
    }

    private void requireAuthor(AuthenticatedUser principal) {
        if (principal.role() == UserRole.STUDENT) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "学生不能创建教学资源");
        }
    }

    private void requireVisible(AuthenticatedUser principal, TeachingResourceEntity entity) {
        courses.requireView(principal,entity.getCourseId());
        if (principal.role() == UserRole.ADMIN
                || entity.getReviewStatus() == ReviewStatus.APPROVED
                || principal.role() == UserRole.TEACHER) {
            return;
        }
        throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "教学资源不存在");
    }

    private void requireOwnerOrAdmin(AuthenticatedUser principal, TeachingResourceEntity entity) {
        courses.requireEdit(principal,entity.getCourseId());

    }

    private TeachingResourceView toView(TeachingResourceEntity entity) {
        FileObjectView file = null;
        if (entity.getFileId() != null) {
            FileObjectEntity fileEntity = fileStorageService.findAvailable(entity.getFileId());
            file = FileObjectView.from(fileEntity);
        }
        UserEntity uploader = userMapper.selectById(entity.getUploaderId());
        return new TeachingResourceView(
                entity.getId(),
                entity.getCourseId(),
                entity.getTitle(),
                entity.getDescription(),
                entity.getResourceType(),
                file,
                entity.getExternalUrl(),
                entity.getDifficulty(),
                bindingMapper.findKnowledgePointIds(entity.getId()),
                entity.getReviewStatus(),
                entity.getReviewComment(),
                uploader == null ? null : UserView.from(uploader),
                entity.getCreatedAt() == null
                        ? null
                        : entity.getCreatedAt().atZone(DATABASE_ZONE).toInstant()
        );
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record ReviewSnapshot(
            String title,
            String description,
            ResourceType resourceType,
            Long fileId,
            String externalUrl,
            Difficulty difficulty,
            ReviewStatus reviewStatus,
            String reviewComment,
            List<String> knowledgePointIds
    ) {
        private static ReviewSnapshot from(TeachingResourceEntity entity, List<String> knowledgePointIds) {
            return new ReviewSnapshot(
                    entity.getTitle(),
                    entity.getDescription(),
                    entity.getResourceType(),
                    entity.getFileId(),
                    entity.getExternalUrl(),
                    entity.getDifficulty(),
                    entity.getReviewStatus(),
                    entity.getReviewComment(),
                    List.copyOf(knowledgePointIds)
            );
        }

        private boolean matches(TeachingResourceEntity entity, List<String> latestKnowledgePointIds) {
            return Objects.equals(title, entity.getTitle())
                    && Objects.equals(description, entity.getDescription())
                    && resourceType == entity.getResourceType()
                    && Objects.equals(fileId, entity.getFileId())
                    && Objects.equals(externalUrl, entity.getExternalUrl())
                    && Objects.equals(difficulty, entity.getDifficulty())
                    && reviewStatus == entity.getReviewStatus()
                    && Objects.equals(reviewComment, entity.getReviewComment())
                    && knowledgePointIds.equals(latestKnowledgePointIds);
        }
    }
}
