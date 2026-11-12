package com.education.agent.auth.controller;

import com.education.agent.auth.dto.AuthData;
import com.education.agent.auth.dto.AuthTokens;
import com.education.agent.auth.dto.ChangePasswordRequest;
import com.education.agent.auth.dto.LoginRequest;
import com.education.agent.auth.dto.RefreshTokenRequest;
import com.education.agent.auth.dto.StudentRegisterRequest;
import com.education.agent.auth.service.AuthenticationService;
import com.education.agent.common.api.ApiResponse;
import com.education.agent.common.api.SimpleData;
import com.education.agent.common.web.RequestIds;
import com.education.agent.security.AuthenticatedUser;
import com.education.agent.user.dto.UserView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationService authenticationService;

    public AuthController(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @PostMapping("/register/student")
    public ResponseEntity<ApiResponse<UserView>> registerStudent(
            @Valid @RequestBody StudentRegisterRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.created(RequestIds.current(), authenticationService.registerStudent(request))
        );
    }

    @PostMapping("/login")
    public ApiResponse<AuthData> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(RequestIds.current(), authenticationService.login(request));
    }

    @PostMapping("/refresh")
    public ApiResponse<AuthTokens> refreshToken(@Valid @RequestBody RefreshTokenRequest request) {
        return ApiResponse.ok(RequestIds.current(), authenticationService.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    public ApiResponse<SimpleData> logout(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody RefreshTokenRequest request
    ) {
        authenticationService.logout(request.refreshToken(), principal.id());
        return ApiResponse.ok(RequestIds.current(), SimpleData.succeeded());
    }

    @GetMapping("/me")
    public ApiResponse<UserView> getCurrentUser(@AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.ok(RequestIds.current(), authenticationService.currentUser(principal.id()));
    }

    @PutMapping("/password")
    public ApiResponse<SimpleData> changePassword(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody ChangePasswordRequest request
    ) {
        authenticationService.changePassword(principal, request);
        return ApiResponse.ok(RequestIds.current(), SimpleData.succeeded());
    }
}
