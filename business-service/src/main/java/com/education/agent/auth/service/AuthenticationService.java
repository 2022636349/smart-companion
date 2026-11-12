package com.education.agent.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.education.agent.admin.service.AuditLogService;
import com.education.agent.auth.dto.AuthData;
import com.education.agent.auth.dto.AuthTokens;
import com.education.agent.auth.dto.ChangePasswordRequest;
import com.education.agent.auth.dto.LoginRequest;
import com.education.agent.auth.dto.StudentRegisterRequest;
import com.education.agent.auth.entity.RefreshTokenEntity;
import com.education.agent.auth.mapper.RefreshTokenMapper;
import com.education.agent.common.error.BusinessException;
import com.education.agent.common.error.ErrorCode;
import com.education.agent.security.AuthenticatedUser;
import com.education.agent.security.JwtService;
import com.education.agent.user.dto.UserView;
import com.education.agent.user.entity.UserEntity;
import com.education.agent.user.mapper.UserMapper;
import com.education.agent.user.model.UserRole;
import com.education.agent.user.model.UserStatus;
import com.education.agent.user.service.UserAccountService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;

@Service
public class AuthenticationService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserMapper userMapper;
    private final UserAccountService userAccountService;
    private final RefreshTokenMapper refreshTokenMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditLogService auditLogService;
    private final long refreshTokenDays;

    public AuthenticationService(
            UserMapper userMapper,
            UserAccountService userAccountService,
            RefreshTokenMapper refreshTokenMapper,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            AuditLogService auditLogService,
            @Value("${education.security.refresh-token-days:14}") long refreshTokenDays
    ) {
        this.userMapper = userMapper;
        this.userAccountService = userAccountService;
        this.refreshTokenMapper = refreshTokenMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.auditLogService = auditLogService;
        this.refreshTokenDays = refreshTokenDays;
    }

    @Transactional
    public UserView registerStudent(StudentRegisterRequest request) {
        if (userAccountService.findByUsername(request.username()) != null) {
            throw new BusinessException(ErrorCode.USERNAME_ALREADY_EXISTS, "用户名已存在");
        }

        UserEntity user = new UserEntity();
        user.setUsername(request.username());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setDisplayName(request.displayName());
        user.setEmail(normalizeNullable(request.email()));
        user.setRole(UserRole.STUDENT);
        user.setStatus(UserStatus.ACTIVE);
        user.setForcePasswordChange(false);
        try {
            userMapper.insert(user);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(ErrorCode.USERNAME_ALREADY_EXISTS, "用户名或邮箱已存在");
        }
        UserEntity saved = userMapper.selectById(user.getId());
        auditLogService.record("USER_REGISTER", "USER", user.getId().toString(), user.getId(), true, Map.of());
        return UserView.from(saved);
    }

    @Transactional
    public AuthData login(LoginRequest request) {
        UserEntity user = userAccountService.findByUsername(request.username());
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            auditLogService.recordLoginAttempt(request.username(), null, false, Map.of());
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, "用户名或密码错误");
        }
        ensureCanLogin(user);
        if (request.expectedRole() != null && request.expectedRole() != user.getRole()) {
            auditLogService.recordLoginAttempt(user.getUsername(), null, false,
                    Map.of("expectedRole", request.expectedRole().name()));
            throw new BusinessException(ErrorCode.EXPECTED_ROLE_MISMATCH, "账号身份与登录入口不匹配");
        }
        AuthData authData = new AuthData(UserView.from(user), issueTokens(user));
        auditLogService.record("USER_LOGIN", "USER", user.getId().toString(), user.getId(), true, Map.of());
        return authData;
    }

    @Transactional
    public AuthTokens refresh(String rawRefreshToken) {
        RefreshTokenEntity current = findActiveRefreshToken(rawRefreshToken);
        LocalDateTime revokedAt = LocalDateTime.now(ZoneOffset.UTC);
        int updated = refreshTokenMapper.update(null, new LambdaUpdateWrapper<RefreshTokenEntity>()
                .eq(RefreshTokenEntity::getId, current.getId())
                .isNull(RefreshTokenEntity::getRevokedAt)
                .set(RefreshTokenEntity::getRevokedAt, revokedAt));
        if (updated != 1) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN, "刷新令牌已被使用");
        }

        UserEntity user = userAccountService.requireById(current.getUserId());
        ensureCanLogin(user);
        return issueTokens(user);
    }

    @Transactional
    public void logout(String rawRefreshToken, Long authenticatedUserId) {
        RefreshTokenEntity token = refreshTokenMapper.selectOne(new LambdaQueryWrapper<RefreshTokenEntity>()
                .eq(RefreshTokenEntity::getTokenHash, sha256Hex(rawRefreshToken)));
        if (token != null && token.getUserId().equals(authenticatedUserId) && token.getRevokedAt() == null) {
            token.setRevokedAt(LocalDateTime.now(ZoneOffset.UTC));
            refreshTokenMapper.updateById(token);
        }
        auditLogService.record("USER_LOGOUT", "USER", authenticatedUserId.toString(), authenticatedUserId, true, Map.of());
    }

    @Transactional
    public void changePassword(AuthenticatedUser principal, ChangePasswordRequest request) {
        UserEntity user = userAccountService.requireById(principal.id());
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.CURRENT_PASSWORD_INCORRECT, "当前密码不正确");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setForcePasswordChange(false);
        userMapper.updateById(user);
        refreshTokenMapper.update(null, new LambdaUpdateWrapper<RefreshTokenEntity>()
                .eq(RefreshTokenEntity::getUserId, user.getId())
                .isNull(RefreshTokenEntity::getRevokedAt)
                .set(RefreshTokenEntity::getRevokedAt, LocalDateTime.now(ZoneOffset.UTC)));
        auditLogService.record("PASSWORD_CHANGE", "USER", user.getId().toString(), user.getId(), true, Map.of());
    }

    public UserView currentUser(Long userId) {
        return UserView.from(userAccountService.requireById(userId));
    }

    private AuthTokens issueTokens(UserEntity user) {
        String rawRefreshToken = newRefreshToken();
        RefreshTokenEntity refreshToken = new RefreshTokenEntity();
        refreshToken.setUserId(user.getId());
        refreshToken.setTokenHash(sha256Hex(rawRefreshToken));
        refreshToken.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusDays(refreshTokenDays));
        refreshTokenMapper.insert(refreshToken);
        return new AuthTokens(
                jwtService.issueAccessToken(user),
                rawRefreshToken,
                "Bearer",
                jwtService.accessTokenSeconds()
        );
    }

    private RefreshTokenEntity findActiveRefreshToken(String rawRefreshToken) {
        RefreshTokenEntity token = refreshTokenMapper.selectOne(new LambdaQueryWrapper<RefreshTokenEntity>()
                .eq(RefreshTokenEntity::getTokenHash, sha256Hex(rawRefreshToken))
                .isNull(RefreshTokenEntity::getRevokedAt));
        if (token == null || !token.getExpiresAt().isAfter(LocalDateTime.now(ZoneOffset.UTC))) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN, "刷新令牌无效或已过期");
        }
        return token;
    }

    private void ensureCanLogin(UserEntity user) {
        if (user.getStatus() == UserStatus.LOCKED) {
            throw new BusinessException(ErrorCode.ACCOUNT_LOCKED, "账号已锁定");
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED, "账号已禁用");
        }
    }

    private static String newRefreshToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
