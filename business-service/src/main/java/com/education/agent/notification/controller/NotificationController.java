package com.education.agent.notification.controller;

import com.education.agent.common.api.*;
import com.education.agent.common.web.RequestIds;
import com.education.agent.notification.dto.NotificationDtos.*;
import com.education.agent.notification.model.NotificationType;
import com.education.agent.notification.service.NotificationService;
import com.education.agent.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated @RestController
public class NotificationController {
    private final NotificationService service;
    public NotificationController(NotificationService service){this.service=service;}
    @GetMapping("/api/notifications")
    public ApiResponse<NotificationPage> list(@AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(defaultValue="1") @Min(1) long page,@RequestParam(defaultValue="20") @Min(1) @Max(100) long size,
            @RequestParam(defaultValue="false") boolean unreadOnly,@RequestParam(required=false) NotificationType type){
        return ApiResponse.ok(RequestIds.current(),service.list(principal,page,size,unreadOnly,type));}
    @PostMapping("/api/notifications/{notificationId}/read")
    public ApiResponse<SimpleData> read(@AuthenticationPrincipal AuthenticatedUser principal,@PathVariable Long notificationId){
        service.markRead(principal,notificationId);return ApiResponse.ok(RequestIds.current(),SimpleData.succeeded());}
    @PostMapping("/api/notifications/read-all")
    public ApiResponse<SimpleData> readAll(@AuthenticationPrincipal AuthenticatedUser principal){service.markAllRead(principal);return ApiResponse.ok(RequestIds.current(),SimpleData.succeeded());}
    @PostMapping("/api/admin/notifications")
    public ResponseEntity<ApiResponse<NotificationPublishResult>> publish(@AuthenticationPrincipal AuthenticatedUser principal,
            @RequestHeader("Idempotency-Key") @Size(min=16,max=64) String key,@Valid @RequestBody NotificationPublishRequest request){
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(RequestIds.current(),service.publish(principal,key,request)));}
}
