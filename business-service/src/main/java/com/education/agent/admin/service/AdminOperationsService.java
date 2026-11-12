package com.education.agent.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.education.agent.admin.dto.AdminDtos.*;
import com.education.agent.admin.entity.AuditLogEntity;
import com.education.agent.admin.mapper.AuditLogMapper;
import com.education.agent.assessment.entity.AssessmentEntity;
import com.education.agent.assessment.mapper.AssessmentMapper;
import com.education.agent.assessment.model.AssessmentStatus;
import com.education.agent.auth.entity.RefreshTokenEntity;
import com.education.agent.auth.mapper.RefreshTokenMapper;
import com.education.agent.classroom.entity.ClassEntity;
import com.education.agent.classroom.mapper.ClassMapper;
import com.education.agent.classroom.model.ClassStatus;
import com.education.agent.common.api.PageData;
import com.education.agent.common.error.BusinessException;
import com.education.agent.common.error.ErrorCode;
import com.education.agent.security.AuthenticatedUser;
import com.education.agent.user.dto.UserView;
import com.education.agent.user.entity.UserEntity;
import com.education.agent.user.mapper.UserMapper;
import com.education.agent.user.model.UserRole;
import com.education.agent.user.model.UserStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
public class AdminOperationsService {
    private static final ZoneId DATABASE_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> SAFE_DETAIL_KEYS = Set.of(
            "version", "questionCount", "count", "status", "reason", "role", "oldRole", "newRole",
            "oldStatus", "newStatus", "assessmentId", "submissionId", "questionId", "memoryId",
            "classId", "studentId", "resourceId", "fileId", "taskId", "requestId", "releasedAt",
            "scheduledReleaseAt", "beforeScore", "finalScore", "expectedRole", "recipientRoles",
            "createdCount", "category", "feedbackStatus", "modelVersion", "promptVersion",
            "sourceCount");
    private static final Set<String> SAFE_AGENT_REQUESTED_MODES = Set.of("FIXED", "ROUTER", "SHADOW");
    private static final Set<String> SAFE_AGENT_EFFECTIVE_MODES = Set.of(
            "FIXED", "ROUTER", "SHADOW", "FIXED_FALLBACK");
    private static final Set<String> SAFE_AGENT_FALLBACKS = Set.of(
            "ROUTER_OUTPUT_INVALID", "TOOL_NOT_ALLOWED", "TOOL_ARGUMENT_INVALID",
            "STEP_LIMIT_REACHED", "TOOL_TIMEOUT", "TOOL_FAILED", "NO_ROUTER_ACTION");
    private static final Set<String> SAFE_AGENT_TOOLS = Set.of(
            "graph.rag_context", "graph.search", "graph.prerequisites",
            "graph.recommend_next", "memory.search");
    private static final Set<String> SAFE_AGENT_TOOL_STATUSES = Set.of(
            "SUCCEEDED", "REJECTED", "FAILED", "TIMED_OUT");

    private final UserMapper users;
    private final RefreshTokenMapper refreshTokens;
    private final AuditLogMapper auditLogs;
    private final ClassMapper classes;
    private final AssessmentMapper assessments;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService audit;
    private final ObjectMapper json;

    public AdminOperationsService(UserMapper users, RefreshTokenMapper refreshTokens,
                                  AuditLogMapper auditLogs, ClassMapper classes,
                                  AssessmentMapper assessments, PasswordEncoder passwordEncoder,
                                  AuditLogService audit, ObjectMapper json) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.auditLogs = auditLogs;
        this.classes = classes;
        this.assessments = assessments;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.json = json;
    }

    public AdminDashboard dashboard() {
        Map<String, Long> roleCounts = new LinkedHashMap<>();
        for (UserRole role : UserRole.values()) {
            roleCounts.put(role.name(), users.selectCount(new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getRole, role)));
        }
        LocalDateTime start = LocalDate.now(DATABASE_ZONE).atStartOfDay();
        long todayLogins = auditLogs.selectCount(new LambdaQueryWrapper<AuditLogEntity>()
                .eq(AuditLogEntity::getAction, "USER_LOGIN").eq(AuditLogEntity::getSuccess, true)
                .ge(AuditLogEntity::getCreatedAt, start));
        long highRisk = auditLogs.selectCount(new LambdaQueryWrapper<AuditLogEntity>()
                .in(AuditLogEntity::getSeverity, "HIGH", "CRITICAL"));
        long activeClasses = classes.selectCount(new LambdaQueryWrapper<ClassEntity>()
                .eq(ClassEntity::getStatus, ClassStatus.ACTIVE));
        long activeAssessments = assessments.selectCount(new LambdaQueryWrapper<AssessmentEntity>()
                .in(AssessmentEntity::getStatus, AssessmentStatus.PUBLISHED, AssessmentStatus.GRADING,
                        AssessmentStatus.READY_TO_RELEASE));
        return new AdminDashboard(Map.copyOf(roleCounts), activeClasses, activeAssessments, todayLogins, highRisk);
    }

    public PageData<UserView> listUsers(long page, long size, UserRole role, UserStatus status, String keyword) {
        String normalized = trimToNull(keyword);
        LambdaQueryWrapper<UserEntity> query = new LambdaQueryWrapper<UserEntity>()
                .eq(role != null, UserEntity::getRole, role)
                .eq(status != null, UserEntity::getStatus, status)
                .and(normalized != null, q -> q.like(UserEntity::getUsername, normalized)
                        .or().like(UserEntity::getDisplayName, normalized)
                        .or().like(UserEntity::getEmail, normalized))
                .orderByDesc(UserEntity::getCreatedAt).orderByDesc(UserEntity::getId);
        Page<UserEntity> result = users.selectPage(Page.of(page, size), query);
        return page(result, result.getRecords().stream().map(UserView::from).toList());
    }

    public UserView getUser(Long userId) { return UserView.from(requireUser(userId)); }

    @Transactional
    public UserView createUser(AuthenticatedUser principal, AdminUserCreateRequest request) {
        UserEntity user = new UserEntity();
        user.setUsername(request.username().trim());
        user.setDisplayName(request.displayName().trim());
        user.setPasswordHash(passwordEncoder.encode(request.initialPassword()));
        user.setEmail(trimToNull(request.email()));
        user.setRole(request.role());
        user.setStatus(UserStatus.ACTIVE);
        user.setForcePasswordChange(true);
        try { users.insert(user); }
        catch (DataIntegrityViolationException exception) {
            throw new BusinessException(ErrorCode.USERNAME_ALREADY_EXISTS, "用户名或邮箱已存在");
        }
        audit.recordRequired("ADMIN_USER_CREATED", "USER", user.getId().toString(), principal.id(), true,
                Map.of("role", user.getRole().name()));
        return UserView.from(requireUser(user.getId()));
    }

    @Transactional
    public UserView updateUser(AuthenticatedUser principal, Long userId, AdminUserUpdateRequest request) {
        List<Long> activeAdmins = users.selectActiveAdminIdsForUpdate();
        UserEntity user = requireLockedUser(userId);
        if (principal.id().equals(userId) && request.role() != UserRole.ADMIN) {
            throw conflict("管理员不能降低自己的权限");
        }
        UserRole previousRole = user.getRole();
        if (previousRole == UserRole.ADMIN && request.role() != UserRole.ADMIN
                && user.getStatus() == UserStatus.ACTIVE) {
            ensureAnotherActiveAdmin(activeAdmins, userId);
        }
        user.setDisplayName(request.displayName().trim());
        user.setEmail(trimToNull(request.email()));
        user.setRole(request.role());
        try { users.updateById(user); }
        catch (DataIntegrityViolationException exception) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT, "邮箱已被使用");
        }
        if (previousRole != request.role()) revokeRefreshTokens(userId);
        audit.recordRequired("ADMIN_USER_UPDATED", "USER", userId.toString(), principal.id(), true,
                Map.of("oldRole", previousRole.name(), "newRole", request.role().name()));
        return UserView.from(requireUser(userId));
    }

    @Transactional
    public UserView updateStatus(AuthenticatedUser principal, Long userId, UserStatusRequest request) {
        List<Long> activeAdmins = users.selectActiveAdminIdsForUpdate();
        UserEntity user = requireLockedUser(userId);
        if (principal.id().equals(userId) && request.status() != UserStatus.ACTIVE) {
            throw conflict("管理员不能禁用或锁定自己");
        }
        UserStatus previous = user.getStatus();
        if (user.getRole() == UserRole.ADMIN && previous == UserStatus.ACTIVE
                && request.status() != UserStatus.ACTIVE) ensureAnotherActiveAdmin(activeAdmins, userId);
        user.setStatus(request.status());
        users.updateById(user);
        revokeRefreshTokens(userId);
        audit.recordRequired("ADMIN_USER_STATUS_UPDATED", "USER", userId.toString(), principal.id(), true,
                Map.of("oldStatus", previous.name(), "newStatus", request.status().name(), "reason", request.reason().trim()));
        return UserView.from(requireUser(userId));
    }

    @Transactional
    public void resetPassword(AuthenticatedUser principal, Long userId, AdminResetPasswordRequest request) {
        UserEntity user = requireLockedUser(userId);
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setForcePasswordChange(request.forceChangeOnNextLogin() == null || request.forceChangeOnNextLogin());
        users.updateById(user);
        revokeRefreshTokens(userId);
        audit.recordRequired("ADMIN_PASSWORD_RESET", "USER", userId.toString(), principal.id(), true, Map.of());
    }

    public PageData<AuditLogView> loginLogs(long page, long size, String username, String status) {
        Boolean success = parseSuccess(status);
        String name = trimToNull(username);
        LambdaQueryWrapper<AuditLogEntity> query = new LambdaQueryWrapper<AuditLogEntity>()
                .eq(AuditLogEntity::getAction, "USER_LOGIN")
                .eq(success != null, AuditLogEntity::getSuccess, success)
                .orderByDesc(AuditLogEntity::getCreatedAt).orderByDesc(AuditLogEntity::getId);
        if (name != null) {
            List<Long> matchingIds = users.selectList(new LambdaQueryWrapper<UserEntity>()
                    .like(UserEntity::getUsername, name).select(UserEntity::getId)).stream().map(UserEntity::getId).toList();
            query.and(q -> q.like(AuditLogEntity::getTargetId, name)
                    .or(!matchingIds.isEmpty(), nested -> nested.in(AuditLogEntity::getOperatorId, matchingIds)));
        }
        return auditPage(page, size, query);
    }

    public PageData<AuditLogView> securityEvents(long page, long size, EventSeverity severity) {
        LambdaQueryWrapper<AuditLogEntity> query = new LambdaQueryWrapper<AuditLogEntity>()
                .eq(severity != null, AuditLogEntity::getSeverity, severity == null ? null : severity.name())
                .and(q -> q.ne(AuditLogEntity::getSeverity, "INFO").or().eq(AuditLogEntity::getSuccess, false))
                .orderByDesc(AuditLogEntity::getCreatedAt).orderByDesc(AuditLogEntity::getId);
        return auditPage(page, size, query);
    }

    public PageData<AuditLogView> auditLogs(long page, long size, String action, Long operatorId) {
        LambdaQueryWrapper<AuditLogEntity> query = new LambdaQueryWrapper<AuditLogEntity>()
                .eq(action != null && !action.isBlank(), AuditLogEntity::getAction, action == null ? null : action.trim())
                .eq(operatorId != null, AuditLogEntity::getOperatorId, operatorId)
                .orderByDesc(AuditLogEntity::getCreatedAt).orderByDesc(AuditLogEntity::getId);
        return auditPage(page, size, query);
    }

    private PageData<AuditLogView> auditPage(long page, long size, LambdaQueryWrapper<AuditLogEntity> query) {
        Page<AuditLogEntity> result = auditLogs.selectPage(Page.of(page, size), query);
        Set<Long> ids = new HashSet<>();
        result.getRecords().forEach(row -> { if (row.getOperatorId() != null) ids.add(row.getOperatorId()); });
        Map<Long, String> names = new HashMap<>();
        if (!ids.isEmpty()) users.selectBatchIds(ids).forEach(user -> names.put(user.getId(), user.getUsername()));
        List<AuditLogView> views = result.getRecords().stream().map(row -> new AuditLogView(
                row.getId(), row.getAction(),
                row.getOperatorId() == null && "USER_LOGIN".equals(row.getAction()) ? safeLoginName(row.getTargetId()) : names.get(row.getOperatorId()),
                row.getOperatorId(), truncate(row.getIpAddress(), 64), row.getSeverity(), row.getSuccess(),
                sanitizedDetails(row.getDetailsJson()), instant(row.getCreatedAt()))).toList();
        return page(result, views);
    }

    private Map<String, Object> sanitizedDetails(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try {
            Map<String, Object> source = json.readValue(raw, new TypeReference<>() {});
            Map<String, Object> result = new LinkedHashMap<>();
            source.forEach((key, value) -> {
                if (SAFE_DETAIL_KEYS.contains(key) && result.size() < 32 && value != null) {
                    if (value instanceof Number || value instanceof Boolean) result.put(key, value);
                    else if (value instanceof String text) result.put(key, truncate(text, 500));
                    else if (value instanceof Collection<?> collection) result.put(key,
                            collection.stream().limit(20).map(item -> truncate(String.valueOf(item), 100)).toList());
                }
            });
            Map<String, Object> agent = sanitizedAgentSummary(source.get("agent"));
            if (!agent.isEmpty() && result.size() < 32) result.put("agent", agent);
            return Map.copyOf(result);
        } catch (Exception ignored) { return Map.of(); }
    }

    /**
     * Admin audit display deliberately accepts only the minimal, already non-sensitive Router
     * summary. It must never become a generic nested-map passthrough for model reasoning,
     * observations, tool arguments, errors, or future trace fields.
     */
    private Map<String, Object> sanitizedAgentSummary(Object raw) {
        if (!(raw instanceof Map<?, ?> source)) return Map.of();
        String requested = safeEnum(source.get("requestedMode"), SAFE_AGENT_REQUESTED_MODES);
        String effective = safeEnum(source.get("effectiveMode"), SAFE_AGENT_EFFECTIVE_MODES);
        Integer steps = safeStepCount(source.get("totalSteps"));
        if (requested == null || effective == null || steps == null) return Map.of();
        List<Map<String, Object>> tools = new ArrayList<>();
        Object rawTools = source.get("tools");
        if (rawTools instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (tools.size() == 2) break;
                Map<String, Object> tool = sanitizedAgentTool(item, tools.size() + 1);
                if (tool != null) tools.add(tool);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requestedMode", requested);
        result.put("effectiveMode", effective);
        result.put("steps", steps);
        result.put("tools", tools);
        String fallback = safeEnum(source.get("fallbackReason"), SAFE_AGENT_FALLBACKS);
        if (fallback != null) result.put("fallbackReason", fallback);
        return Map.copyOf(result);
    }

    private Map<String, Object> sanitizedAgentTool(Object raw, int expectedStep) {
        if (!(raw instanceof Map<?, ?> source)) return null;
        Integer step = safeStepCount(source.get("step"));
        String tool = safeEnum(source.get("tool"), SAFE_AGENT_TOOLS);
        String reasonCode = safeMachineCode(source.get("reasonCode"));
        String status = safeEnum(source.get("status"), SAFE_AGENT_TOOL_STATUSES);
        Integer durationMs = safeInteger(source.get("durationMs"), Integer.MAX_VALUE);
        Integer itemCount = safeInteger(source.get("itemCount"), 1000);
        String errorCode = safeMachineCode(source.get("errorCode"));
        if (step == null || step != expectedStep || tool == null || reasonCode == null
                || status == null || durationMs == null || itemCount == null
                || "SUCCEEDED".equals(status) && errorCode != null
                || !"SUCCEEDED".equals(status) && errorCode == null) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("step", step);
        result.put("tool", tool);
        result.put("reasonCode", reasonCode);
        result.put("status", status);
        result.put("durationMs", durationMs);
        result.put("itemCount", itemCount);
        if (errorCode != null) result.put("errorCode", errorCode);
        return Map.copyOf(result);
    }

    private Integer safeStepCount(Object raw) {
        if (!(raw instanceof Number number)) return null;
        int value = number.intValue();
        return number.doubleValue() == value && value >= 0 && value <= 2 ? value : null;
    }

    private Integer safeInteger(Object raw, int maximum) {
        if (!(raw instanceof Number number)) return null;
        int value = number.intValue();
        return number.doubleValue() == value && value >= 0 && value <= maximum ? value : null;
    }

    private String safeMachineCode(Object raw) {
        return raw instanceof String value && value.matches("[A-Z][A-Z0-9_]{0,79}") ? value : null;
    }

    private String safeEnum(Object raw, Set<String> allowed) {
        return raw instanceof String value && allowed.contains(value) ? value : null;
    }

    private void ensureAnotherActiveAdmin(List<Long> active, Long targetId) {
        if (active.stream().noneMatch(id -> !id.equals(targetId))) throw conflict("不能移除最后一个启用中的管理员");
    }

    private void revokeRefreshTokens(Long userId) {
        refreshTokens.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<RefreshTokenEntity>()
                .eq(RefreshTokenEntity::getUserId, userId).isNull(RefreshTokenEntity::getRevokedAt)
                .set(RefreshTokenEntity::getRevokedAt, LocalDateTime.now()));
    }

    private UserEntity requireLockedUser(Long id) {
        UserEntity user = users.selectForUpdate(id);
        if (user == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "用户不存在");
        return user;
    }
    private UserEntity requireUser(Long id) {
        UserEntity user = users.selectById(id);
        if (user == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "用户不存在");
        return user;
    }
    private static Boolean parseSuccess(String status) {
        if (status == null || status.isBlank()) return null;
        return switch (status.trim().toUpperCase(Locale.ROOT)) {
            case "SUCCESS", "SUCCEEDED", "TRUE" -> true;
            case "FAILED", "FAILURE", "FALSE" -> false;
            default -> throw new BusinessException(ErrorCode.VALIDATION_ERROR, "status 仅支持 SUCCESS 或 FAILED");
        };
    }
    private static <T> PageData<T> page(Page<?> page, List<T> items) {
        return new PageData<>(items, page.getCurrent(), page.getSize(), page.getTotal(), page.getPages());
    }
    private static Instant instant(LocalDateTime time) { return time == null ? null : time.atZone(DATABASE_ZONE).toInstant(); }
    private static String trimToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String safeLoginName(String value) { return truncate(value, 64); }
    private static String truncate(String value, int max) { return value == null ? null : value.substring(0, Math.min(value.length(), max)); }
    private static BusinessException conflict(String message) { return new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT, message); }
}
