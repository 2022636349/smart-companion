package com.education.agent.admin.controller;

import com.education.agent.admin.dto.AdminDtos.*;
import com.education.agent.admin.service.AdminOperationsService;
import com.education.agent.common.api.*;
import com.education.agent.common.web.RequestIds;
import com.education.agent.security.AuthenticatedUser;
import com.education.agent.user.dto.UserView;
import com.education.agent.user.model.UserRole;
import com.education.agent.user.model.UserStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequestMapping("/api/admin")
public class AdminOperationsController {
    private final AdminOperationsService service;
    public AdminOperationsController(AdminOperationsService service) { this.service = service; }

    @GetMapping("/dashboard")
    public ApiResponse<AdminDashboard> dashboard() { return ApiResponse.ok(RequestIds.current(), service.dashboard()); }

    @GetMapping("/users")
    public ApiResponse<PageData<UserView>> users(@RequestParam(defaultValue="1") @Min(1) long page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) long size,
            @RequestParam(required=false) UserRole role, @RequestParam(required=false) UserStatus status,
            @RequestParam(required=false) String keyword) {
        return ApiResponse.ok(RequestIds.current(), service.listUsers(page, size, role, status, keyword));
    }
    @PostMapping("/users")
    public ResponseEntity<ApiResponse<UserView>> create(@AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody AdminUserCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(RequestIds.current(), service.createUser(principal, request)));
    }
    @GetMapping("/users/{userId}")
    public ApiResponse<UserView> user(@PathVariable Long userId) { return ApiResponse.ok(RequestIds.current(), service.getUser(userId)); }
    @PutMapping("/users/{userId}")
    public ApiResponse<UserView> update(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable Long userId,
            @Valid @RequestBody AdminUserUpdateRequest request) {
        return ApiResponse.ok(RequestIds.current(), service.updateUser(principal, userId, request));
    }
    @PutMapping("/users/{userId}/status")
    public ApiResponse<UserView> status(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable Long userId,
            @Valid @RequestBody UserStatusRequest request) {
        return ApiResponse.ok(RequestIds.current(), service.updateStatus(principal, userId, request));
    }
    @PostMapping("/users/{userId}/reset-password")
    public ApiResponse<SimpleData> reset(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable Long userId,
            @Valid @RequestBody AdminResetPasswordRequest request) {
        service.resetPassword(principal, userId, request);
        return ApiResponse.ok(RequestIds.current(), SimpleData.succeeded());
    }
    @GetMapping("/login-logs")
    public ApiResponse<PageData<AuditLogView>> loginLogs(@RequestParam(defaultValue="1") @Min(1) long page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) long size,
            @RequestParam(required=false) String username, @RequestParam(required=false) String status) {
        return ApiResponse.ok(RequestIds.current(), service.loginLogs(page,size,username,status));
    }
    @GetMapping("/security-events")
    public ApiResponse<PageData<AuditLogView>> security(@RequestParam(defaultValue="1") @Min(1) long page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) long size,
            @RequestParam(required=false) EventSeverity severity) {
        return ApiResponse.ok(RequestIds.current(), service.securityEvents(page,size,severity));
    }
    @GetMapping("/audit-logs")
    public ApiResponse<PageData<AuditLogView>> audit(@RequestParam(defaultValue="1") @Min(1) long page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) long size,
            @RequestParam(required=false) String action, @RequestParam(required=false) Long operatorId) {
        return ApiResponse.ok(RequestIds.current(), service.auditLogs(page,size,action,operatorId));
    }
}
