package com.education.agent.admin.service;

import com.education.agent.admin.entity.AuditLogEntity;
import com.education.agent.admin.mapper.AuditLogMapper;
import com.education.agent.common.web.RequestIds;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;

@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private final AuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper;

    public AuditLogService(AuditLogMapper auditLogMapper, ObjectMapper objectMapper) {
        this.auditLogMapper = auditLogMapper;
        this.objectMapper = objectMapper;
    }

    public void record(
            String action,
            String targetType,
            String targetId,
            Long operatorId,
            boolean success,
            Map<String, Object> details
    ) {
        try {
            AuditLogEntity entity = new AuditLogEntity();
            entity.setAction(action);
            entity.setTargetType(targetType);
            entity.setTargetId(targetId);
            entity.setOperatorId(operatorId);
            entity.setIpAddress(currentIp());
            entity.setSeverity(success ? "INFO" : "MEDIUM");
            entity.setSuccess(success);
            entity.setRequestId(RequestIds.current());
            entity.setDetailsJson(objectMapper.writeValueAsString(details));
            auditLogMapper.insert(entity);
        } catch (Exception exception) {
            log.warn("Audit log persistence failed for action {}", action, exception);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordLoginAttempt(String targetId, Long operatorId, boolean success, Map<String, Object> details) {
        record("USER_LOGIN", "USER", targetId, operatorId, success, details);
    }

    /** Critical administrative mutations use this variant so audit persistence participates in the transaction. */
    public void recordRequired(
            String action,
            String targetType,
            String targetId,
            Long operatorId,
            boolean success,
            Map<String, Object> details
    ) {
        AuditLogEntity entity = new AuditLogEntity();
        entity.setAction(action);
        entity.setTargetType(targetType);
        entity.setTargetId(targetId);
        entity.setOperatorId(operatorId);
        entity.setIpAddress(currentIp());
        entity.setSeverity(success ? "INFO" : "MEDIUM");
        entity.setSuccess(success);
        entity.setRequestId(RequestIds.current());
        try {
            entity.setDetailsJson(objectMapper.writeValueAsString(details));
        } catch (Exception exception) {
            throw new IllegalStateException("审计详情序列化失败", exception);
        }
        auditLogMapper.insert(entity);
    }

    private String currentIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            String forwarded = request.getHeader("X-Forwarded-For");
            return forwarded == null || forwarded.isBlank()
                    ? request.getRemoteAddr()
                    : forwarded.split(",")[0].trim();
        }
        return null;
    }
}
