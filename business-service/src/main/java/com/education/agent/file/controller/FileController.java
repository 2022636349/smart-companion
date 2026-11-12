package com.education.agent.file.controller;

import com.education.agent.common.api.ApiResponse;
import com.education.agent.common.api.SimpleData;
import com.education.agent.common.error.BusinessException;
import com.education.agent.common.error.ErrorCode;
import com.education.agent.common.web.RequestIds;
import com.education.agent.file.dto.FileDownload;
import com.education.agent.file.dto.FileObjectView;
import com.education.agent.file.model.FilePurpose;
import com.education.agent.file.service.FileStorageService;
import com.education.agent.security.AuthenticatedUser;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

@RestController
@RequestMapping("/api/files")
public class FileController {

    private static final Set<String> SAFE_INLINE_MEDIA_TYPES = Set.of(
            "text/plain",
            "application/json",
            "image/png",
            "image/jpeg",
            "image/gif",
            "image/webp",
            "video/mp4",
            "video/webm"
    );

    private final FileStorageService fileStorageService;
    private final byte[] internalToken;

    public FileController(
            FileStorageService fileStorageService,
            @Value("${education.ai-service.internal-token:}") String internalToken
    ) {
        this.fileStorageService = fileStorageService;
        this.internalToken = internalToken.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<FileObjectView>> upload(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam("file") MultipartFile file,
            @RequestParam FilePurpose purpose
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.created(RequestIds.current(), fileStorageService.store(principal, file, purpose))
        );
    }

    @GetMapping("/{fileId}")
    public ApiResponse<FileObjectView> metadata(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long fileId
    ) {
        return ApiResponse.ok(RequestIds.current(), fileStorageService.metadata(principal, fileId));
    }

    @GetMapping("/{fileId}/content")
    public ResponseEntity<Resource> content(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long fileId,
            @RequestHeader(value = "X-Internal-Token", required = false) String suppliedInternalToken,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId
    ) {
        FileDownload download;
        if (principal != null) {
            download = fileStorageService.content(principal, fileId);
        } else {
            if (!validInternalToken(suppliedInternalToken) || requestId == null || requestId.isBlank()) {
                throw new BusinessException(ErrorCode.UNAUTHENTICATED, "下载文件需要有效身份");
            }
            download = fileStorageService.contentForActiveInternalTask(fileId, requestId);
        }
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(download.contentType());
        } catch (IllegalArgumentException exception) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }
        boolean safeInline = SAFE_INLINE_MEDIA_TYPES.contains(mediaType.toString());
        ContentDisposition disposition = (safeInline
                ? ContentDisposition.inline()
                : ContentDisposition.attachment())
                .filename(download.originalName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(download.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(download.path()));
    }

    private boolean validInternalToken(String supplied) {
        if (internalToken.length == 0 || supplied == null || supplied.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(internalToken, supplied.getBytes(StandardCharsets.UTF_8));
    }

    @DeleteMapping("/{fileId}")
    public ApiResponse<SimpleData> delete(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long fileId
    ) {
        fileStorageService.delete(principal, fileId);
        return ApiResponse.ok(RequestIds.current(), SimpleData.succeeded());
    }
}
