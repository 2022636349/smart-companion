package com.education.agent.tutor.controller;

import com.education.agent.common.api.ApiResponse;
import com.education.agent.common.api.PageData;
import com.education.agent.common.api.SimpleData;
import com.education.agent.common.error.BusinessException;
import com.education.agent.common.error.ErrorCode;
import com.education.agent.common.web.RequestIds;
import com.education.agent.security.AuthenticatedUser;
import com.education.agent.tutor.dto.TutorMessageRequest;
import com.education.agent.tutor.dto.TutorReply;
import com.education.agent.tutor.dto.TutorSessionCreateRequest;
import com.education.agent.tutor.dto.TutorSessionView;
import com.education.agent.tutor.dto.TutorStreamEvent;
import com.education.agent.tutor.service.TutorService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Validated
@RestController
@RequestMapping("/api/tutor/sessions")
public class TutorController {

    // The SSE connection must outlive Spring's 150-second AI read budget. The
    // browser keeps a slightly larger 160-second deadline.
    private static final long STREAM_TIMEOUT_MILLIS = 155_000L;
    private static final int STREAM_CHUNK_CODE_POINTS = 16;
    private static final long STREAM_CHUNK_DELAY_MILLIS = 20L;
    private static final long STREAM_HEARTBEAT_SECONDS = 10L;

    private final TutorService tutorService;
    private final Executor tutorStreamExecutor;
    private final ScheduledExecutorService tutorStreamHeartbeatExecutor;

    public TutorController(
            TutorService tutorService,
            @Qualifier("tutorStreamExecutor") Executor tutorStreamExecutor,
            @Qualifier("tutorStreamHeartbeatExecutor")
            ScheduledExecutorService tutorStreamHeartbeatExecutor
    ) {
        this.tutorService = tutorService;
        this.tutorStreamExecutor = tutorStreamExecutor;
        this.tutorStreamHeartbeatExecutor = tutorStreamHeartbeatExecutor;
    }

    @GetMapping
    public ApiResponse<PageData<TutorSessionView>> list(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam @Min(1) Long courseId,
            @RequestParam(defaultValue = "1") @Min(1) long page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) long size,
            @RequestParam(required = false) String knowledgePointId
    ) {
        return ApiResponse.ok(
                RequestIds.current(),
                tutorService.list(principal, courseId, page, size, knowledgePointId)
        );
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TutorSessionView>> create(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) Long courseId,
            @Valid @RequestBody TutorSessionCreateRequest request
    ) {
        if (courseId != null && !courseId.equals(request.courseId())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "查询课程与请求课程不一致");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.created(RequestIds.current(), tutorService.create(principal, request))
        );
    }

    @GetMapping("/{sessionId}")
    public ApiResponse<TutorSessionView> get(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long sessionId
    ) {
        return ApiResponse.ok(RequestIds.current(), tutorService.get(principal, sessionId));
    }

    @DeleteMapping("/{sessionId}")
    public ApiResponse<SimpleData> delete(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long sessionId
    ) {
        tutorService.delete(principal, sessionId);
        return ApiResponse.ok(RequestIds.current(), SimpleData.succeeded());
    }

    @PostMapping("/{sessionId}/messages")
    public ApiResponse<TutorReply> sendMessage(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long sessionId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String idempotencyKey,
            @Valid @RequestBody TutorMessageRequest request
    ) {
        String requestId = RequestIds.current();
        return ApiResponse.ok(
                requestId,
                tutorService.sendMessage(
                        principal, sessionId, idempotencyKey, request.message(), requestId,
                        request.teachingMode())
        );
    }

    @PostMapping(value = "/{sessionId}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMessage(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long sessionId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String idempotencyKey,
            @Valid @RequestBody TutorMessageRequest request,
            HttpServletResponse response
    ) {
        String requestId = RequestIds.current();
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        try {
            tutorStreamExecutor.execute(() -> emitReply(
                    emitter, principal, sessionId, idempotencyKey, request.message(), requestId,
                    request.teachingMode()
            ));
        } catch (TaskRejectedException exception) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "AI 导师流式请求繁忙，请稍后重试");
        }
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("X-Request-Id", requestId);
        return emitter;
    }

    private void emitReply(
            SseEmitter emitter,
            AuthenticatedUser principal,
            Long sessionId,
            String idempotencyKey,
            String message,
            String requestId,
            com.education.agent.tutor.model.TutorTeachingMode teachingMode
    ) {
        ScheduledFuture<?> heartbeat = null;
        try {
            sendEvent(emitter, "started", TutorStreamEvent.started(requestId));
            heartbeat = tutorStreamHeartbeatExecutor.scheduleAtFixedRate(
                    () -> sendHeartbeat(emitter),
                    STREAM_HEARTBEAT_SECONDS,
                    STREAM_HEARTBEAT_SECONDS,
                    TimeUnit.SECONDS
            );
            TutorReply reply = tutorService.sendMessage(
                    principal, sessionId, idempotencyKey, message, requestId, teachingMode
            );
            List<String> chunks = chunks(reply.assistantMessage().content());
            for (int index = 0; index < chunks.size(); index++) {
                String chunk = chunks.get(index);
                sendEvent(emitter, "delta", TutorStreamEvent.delta(requestId, chunk));
                if (index + 1 < chunks.size()) pauseBetweenChunks();
            }
            sendEvent(emitter, "complete", TutorStreamEvent.complete(requestId, reply));
            emitter.complete();
        } catch (BusinessException exception) {
            sendError(emitter, TutorStreamEvent.error(
                    requestId,
                    exception.errorCode().name(),
                    exception.getMessage(),
                    exception.errorCode().status().value()
            ));
        } catch (IOException exception) {
            emitter.completeWithError(exception);
        } catch (RuntimeException exception) {
            sendError(emitter, TutorStreamEvent.error(
                    requestId,
                    ErrorCode.INTERNAL_ERROR.name(),
                    "导师回复流未能完成，请使用相同消息重试",
                    ErrorCode.INTERNAL_ERROR.status().value()
            ));
        } finally {
            if (heartbeat != null) heartbeat.cancel(false);
        }
    }

    private void sendHeartbeat(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().comment("keep-alive"));
        } catch (IOException | IllegalStateException ignored) {
            // The request worker owns completion. A disconnected browser is not
            // allowed to cancel or roll back an already-running idempotent call.
        }
    }

    private void pauseBetweenChunks() {
        try {
            Thread.sleep(STREAM_CHUNK_DELAY_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "导师回复流被中断，请重试");
        }
    }

    private void sendError(SseEmitter emitter, TutorStreamEvent event) {
        try {
            sendEvent(emitter, "error", event);
            emitter.complete();
        } catch (IOException | IllegalStateException exception) {
            emitter.completeWithError(exception);
        }
    }

    private void sendEvent(SseEmitter emitter, String name, TutorStreamEvent event) throws IOException {
        emitter.send(SseEmitter.event().name(name).data(event, MediaType.APPLICATION_JSON));
    }

    private List<String> chunks(String value) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        int start = 0;
        while (start < value.length()) {
            int remaining = value.codePointCount(start, value.length());
            int count = Math.min(STREAM_CHUNK_CODE_POINTS, remaining);
            int end = value.offsetByCodePoints(start, count);
            result.add(value.substring(start, end));
            start = end;
        }
        return List.copyOf(result);
    }

    @PostMapping("/{sessionId}/close")
    public ApiResponse<TutorSessionView> close(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long sessionId
    ) {
        return ApiResponse.ok(RequestIds.current(), tutorService.close(principal, sessionId));
    }
}
