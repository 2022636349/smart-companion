package com.education.agent.file.service;

import com.education.agent.common.error.BusinessException;
import com.education.agent.common.error.ErrorCode;
import com.education.agent.file.dto.FileDownload;
import com.education.agent.file.dto.FileObjectView;
import com.education.agent.file.entity.FileObjectEntity;
import com.education.agent.file.mapper.FileObjectMapper;
import com.education.agent.file.model.FilePurpose;
import com.education.agent.file.model.FileStatus;
import com.education.agent.security.AuthenticatedUser;
import com.education.agent.user.model.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);
    private static final Map<FilePurpose, Set<String>> ALLOWED_EXTENSIONS = Map.of(
            FilePurpose.ASSESSMENT_SOURCE, Set.of("pdf", "docx", "txt", "md"),
            FilePurpose.KNOWLEDGE_SOURCE, Set.of("pdf", "docx", "txt", "md"),
            FilePurpose.SUBMISSION_ATTACHMENT, Set.of("pdf", "docx", "txt", "md", "png", "jpg", "jpeg", "zip", "py"),
            FilePurpose.TEACHING_RESOURCE, Set.of("pdf", "docx", "txt", "md", "png", "jpg", "jpeg", "gif", "webp", "mp4", "webm", "py", "json"),
            FilePurpose.AVATAR, Set.of("png", "jpg", "jpeg", "webp"),
            FilePurpose.FEEDBACK_ATTACHMENT, Set.of("pdf", "docx", "txt", "md", "png", "jpg", "jpeg", "zip")
    );
    private static final Map<String, String> CONTENT_TYPES_BY_EXTENSION = Map.ofEntries(
            Map.entry("pdf", "application/pdf"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("txt", "text/plain"),
            Map.entry("md", "text/plain"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("mp4", "video/mp4"),
            Map.entry("webm", "video/webm"),
            Map.entry("zip", "application/zip"),
            Map.entry("py", "text/plain"),
            Map.entry("json", "application/json"),
            // HTML is retained as an authoring resource, but is always served as an
            // opaque attachment by FileController. It must never execute same-origin.
            Map.entry("html", "application/octet-stream")
    );

    private final FileObjectMapper fileObjectMapper;
    private final Path storageRoot;
    private final long maxBytes;

    public FileStorageService(
            FileObjectMapper fileObjectMapper,
            @Value("${education.file-storage.root:./storage}") String storageRoot,
            @Value("${education.file-storage.max-bytes:52428800}") long maxBytes
    ) {
        this.fileObjectMapper = fileObjectMapper;
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
    }

    @Transactional
    public FileObjectView store(AuthenticatedUser principal, MultipartFile file, FilePurpose purpose) {
        requireUploadPermission(principal, purpose);
        validate(file, purpose);

        String originalName = sanitizeName(file.getOriginalFilename());
        String extension = extensionOf(originalName);
        String storageKey = principal.id() + "/" + UUID.randomUUID() + (extension.isEmpty() ? "" : "." + extension);
        Path target = resolveStorageKey(storageKey);
        Path temporary = target.resolveSibling(target.getFileName() + ".uploading");

        try {
            Files.createDirectories(target.getParent());
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            moveIntoPlace(temporary, target);
        } catch (IOException exception) {
            deleteQuietly(temporary);
            throw storageFailure(exception);
        }

        registerRollbackCleanup(target);

        FileObjectEntity entity = new FileObjectEntity();
        entity.setOriginalName(originalName);
        entity.setStorageKey(storageKey);
        // Multipart Content-Type is uploader-controlled. Derive the stored type from
        // the validated extension so a renamed/forged upload cannot become active HTML.
        entity.setContentType(contentTypeFor(extension));
        entity.setSizeBytes(file.getSize());
        entity.setSha256(sha256(target));
        entity.setOwnerId(principal.id());
        entity.setPurpose(purpose);
        entity.setStatus(FileStatus.AVAILABLE);
        fileObjectMapper.insert(entity);
        return FileObjectView.from(requireEntity(entity.getId()));
    }

    public FileObjectView metadata(AuthenticatedUser principal, Long fileId) {
        FileObjectEntity entity = requireAvailable(fileId);
        requireReadPermission(principal, entity);
        return FileObjectView.from(entity);
    }

    public FileDownload content(AuthenticatedUser principal, Long fileId) {
        FileObjectEntity entity = requireAvailable(fileId);
        requireReadPermission(principal, entity);
        return download(entity);
    }

    public FileDownload contentForActiveAssessmentImport(Long fileId, String requestId) {
        FileObjectEntity entity = requireAvailable(fileId);
        if (entity.getPurpose() != FilePurpose.ASSESSMENT_SOURCE
                || fileObjectMapper.countActiveAssessmentImportReferences(fileId, requestId) == 0) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "导入文件不存在或任务已失效");
        }
        return download(entity);
    }

    public FileDownload contentForActiveInternalTask(Long fileId, String requestId) {
        FileObjectEntity entity = requireAvailable(fileId);
        boolean assessmentImport = entity.getPurpose() == FilePurpose.ASSESSMENT_SOURCE
                && fileObjectMapper.countActiveAssessmentImportReferences(fileId, requestId) > 0;
        boolean aiGrade = entity.getPurpose() == FilePurpose.SUBMISSION_ATTACHMENT
                && fileObjectMapper.countActiveAiGradeReferences(fileId, requestId) > 0;
        boolean graphIngestion = entity.getPurpose() == FilePurpose.KNOWLEDGE_SOURCE
                && fileObjectMapper.countActiveGraphIngestionReferences(fileId, requestId) > 0;
        if (!assessmentImport && !aiGrade && !graphIngestion) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "内部文件不存在或任务已失效");
        }
        return download(entity);
    }

    private FileDownload download(FileObjectEntity entity) {
        Path path = resolveStorageKey(entity.getStorageKey());
        if (!Files.isRegularFile(path)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "文件内容不存在");
        }
        String type=entity.getContentType();String extension=extensionOf(entity.getOriginalName());
        if(entity.getPurpose()==FilePurpose.TEACHING_RESOURCE
                && ("html".equals(extension)||"htm".equals(extension)||"text/html".equalsIgnoreCase(type))){
            type="application/octet-stream";
        }
        return new FileDownload(path, entity.getOriginalName(), type, entity.getSizeBytes());
    }

    @Transactional
    public void delete(AuthenticatedUser principal, Long fileId) {
        FileObjectEntity entity = requireAvailable(fileId);
        requireOwnerOrAdmin(principal, entity);
        if (fileObjectMapper.countBusinessReferences(fileId) > 0) {
            throw new BusinessException(ErrorCode.RESOURCE_IN_USE, "文件已被业务数据引用，不能删除");
        }
        entity.setStatus(FileStatus.DELETED);
        entity.setDeletedAt(LocalDateTime.now());
        fileObjectMapper.updateById(entity);
        Path path = resolveStorageKey(entity.getStorageKey());
        afterCommit(() -> deleteQuietly(path));
    }

    /** Business-owned cleanup used after the referencing aggregate has been deleted. */
    @Transactional
    public void deleteUnreferencedManaged(Long fileId) {
        FileObjectEntity entity = fileObjectMapper.selectById(fileId);
        if (entity == null || entity.getStatus() != FileStatus.AVAILABLE) {
            return;
        }
        if (fileObjectMapper.countBusinessReferences(fileId) > 0) {
            throw new BusinessException(ErrorCode.RESOURCE_IN_USE, "文件仍被业务数据引用，不能清理");
        }
        entity.setStatus(FileStatus.DELETED);
        entity.setDeletedAt(LocalDateTime.now());
        fileObjectMapper.updateById(entity);
        Path path = resolveStorageKey(entity.getStorageKey());
        afterCommit(() -> deleteQuietly(path));
    }

    public FileObjectEntity requireOwnedAvailable(Long fileId, AuthenticatedUser principal, FilePurpose expectedPurpose) {
        FileObjectEntity entity = requireAvailable(fileId);
        if (!(expectedPurpose == FilePurpose.KNOWLEDGE_SOURCE && principal.role() == UserRole.TEACHER
                && fileObjectMapper.countEditablePackageReferences(fileId, principal.id()) > 0)) {
            requireOwnerOrAdmin(principal, entity);
        }
        if (entity.getPurpose() != expectedPurpose) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "文件用途不匹配",
                    Map.of("expectedPurpose", expectedPurpose.name(), "actualPurpose", entity.getPurpose().name())
            );
        }
        return entity;
    }

    public FileObjectEntity findAvailable(Long fileId) {
        return requireAvailable(fileId);
    }

    private FileObjectEntity requireAvailable(Long fileId) {
        FileObjectEntity entity = requireEntity(fileId);
        if (entity.getStatus() != FileStatus.AVAILABLE) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "文件不存在");
        }
        return entity;
    }

    private FileObjectEntity requireEntity(Long fileId) {
        FileObjectEntity entity = fileObjectMapper.selectById(fileId);
        if (entity == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "文件不存在");
        }
        return entity;
    }

    private void requireUploadPermission(AuthenticatedUser principal, FilePurpose purpose) {
        if (purpose == FilePurpose.KNOWLEDGE_SOURCE && principal.role() == UserRole.STUDENT) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "学生不能上传知识图谱来源文件");
        }
        if ((purpose == FilePurpose.ASSESSMENT_SOURCE || purpose == FilePurpose.TEACHING_RESOURCE)
                && principal.role() == UserRole.STUDENT) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "当前角色不能上传此用途的文件");
        }
        if (purpose == FilePurpose.SUBMISSION_ATTACHMENT
                && principal.role() != UserRole.STUDENT
                && principal.role() != UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有学生可以上传答题附件");
        }
    }

    private void requireReadPermission(AuthenticatedUser principal, FileObjectEntity entity) {
        if (principal.role() == UserRole.ADMIN || entity.getOwnerId().equals(principal.id())) {
            return;
        }
        if (fileObjectMapper.countApprovedResourceReferences(entity.getId(), principal.id()) > 0) {
            return;
        }
        if (principal.role() == UserRole.TEACHER
                && entity.getPurpose() == FilePurpose.TEACHING_RESOURCE
                && fileObjectMapper.countTeacherResourceReferences(entity.getId(), principal.id()) > 0) {
            return;
        }
        if (principal.role() == UserRole.TEACHER
                && entity.getPurpose() == FilePurpose.SUBMISSION_ATTACHMENT
                && fileObjectMapper.countTeacherSubmissionAttachmentReferences(entity.getId(), principal.id()) > 0) {
            return;
        }
        throw new BusinessException(ErrorCode.RESOURCE_NOT_OWNED, "无权访问该文件");
    }

    private void requireOwnerOrAdmin(AuthenticatedUser principal, FileObjectEntity entity) {
        if (principal.role() != UserRole.ADMIN && !entity.getOwnerId().equals(principal.id())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_OWNED, "无权操作该文件");
        }
    }

    private void validate(MultipartFile file, FilePurpose purpose) {
        if (file == null || file.isEmpty() || file.getSize() <= 0) {
            throw new BusinessException(ErrorCode.FILE_EMPTY, "不能上传空文件");
        }
        if (file.getSize() > maxBytes) {
            throw new BusinessException(
                    ErrorCode.FILE_TOO_LARGE,
                    "文件超过允许大小",
                    Map.of("maxBytes", maxBytes)
            );
        }
        String extension = extensionOf(sanitizeName(file.getOriginalFilename()));
        if (!ALLOWED_EXTENSIONS.get(purpose).contains(extension)) {
            throw new BusinessException(
                    ErrorCode.UNSUPPORTED_FILE_TYPE,
                    "此文件类型不支持当前用途",
                    Map.of("extension", extension.isBlank() ? "none" : extension, "purpose", purpose.name())
            );
        }
    }

    private String sanitizeName(String suppliedName) {
        String name = StringUtils.hasText(suppliedName) ? suppliedName : "unnamed";
        name = name.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).trim();
        if (name.isBlank() || name.contains("..") || name.length() > 255) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "文件名不合法");
        }
        return name;
    }

    private String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1
                ? ""
                : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String contentTypeFor(String extension) {
        return CONTENT_TYPES_BY_EXTENSION.getOrDefault(extension, "application/octet-stream");
    }

    private Path resolveStorageKey(String storageKey) {
        Path resolved = storageRoot.resolve(storageKey).normalize();
        if (!resolved.startsWith(storageRoot)) {
            throw new BusinessException(ErrorCode.FILE_STORAGE_ERROR, "文件存储路径不合法");
        }
        return resolved;
    }

    private String sha256(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw storageFailure(exception);
        }
    }

    private void moveIntoPlace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void registerRollbackCleanup(Path target) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    deleteQuietly(target);
                }
            }
        });
    }

    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            log.warn("Unable to remove managed file {}", path, exception);
        }
    }

    private BusinessException storageFailure(Exception exception) {
        return new BusinessException(
                ErrorCode.FILE_STORAGE_ERROR,
                "文件存储失败",
                Map.of("cause", exception.getClass().getSimpleName())
        );
    }
}
