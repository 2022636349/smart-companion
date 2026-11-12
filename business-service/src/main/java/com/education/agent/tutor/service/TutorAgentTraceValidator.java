package com.education.agent.tutor.service;

import com.education.agent.common.error.BusinessException;
import com.education.agent.common.error.ErrorCode;
import com.education.agent.integration.ai.AiTutorAgentTrace;
import com.education.agent.integration.ai.AiTutorToolInvocation;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class TutorAgentTraceValidator {
    private static final Set<String> TOOLS = Set.of(
            "graph.rag_context", "graph.search", "graph.prerequisites",
            "graph.recommend_next", "memory.search");
    private static final Set<String> FALLBACKS = Set.of(
            "ROUTER_OUTPUT_INVALID", "TOOL_NOT_ALLOWED", "TOOL_ARGUMENT_INVALID",
            "STEP_LIMIT_REACHED", "TOOL_TIMEOUT", "TOOL_FAILED", "NO_ROUTER_ACTION");
    private static final Set<String> STATUSES =
            Set.of("SUCCEEDED", "REJECTED", "FAILED", "TIMED_OUT");

    public void validate(String expectedMode, AiTutorAgentTrace trace) {
        if (trace == null || !expectedMode.equals(trace.requestedMode())
                || trace.effectiveMode() == null || trace.toolInvocations() == null
                || trace.totalSteps() == null || trace.totalSteps() < 0 || trace.totalSteps() > 2
                || trace.totalSteps() != trace.toolInvocations().size()
                || trace.toolInvocations().size() > 2) {
            throw invalid("agent_trace 基础字段或 requested_mode 不合法");
        }
        boolean compatible = switch (expectedMode) {
            case "FIXED" -> "FIXED".equals(trace.effectiveMode());
            case "ROUTER" -> Set.of("ROUTER", "FIXED_FALLBACK").contains(trace.effectiveMode());
            case "SHADOW" -> "SHADOW".equals(trace.effectiveMode());
            default -> false;
        };
        if (!compatible) throw invalid("agent_trace requested/effective 模式组合不合法");
        String fallback = trim(trace.fallbackReason());
        if ("FIXED_FALLBACK".equals(trace.effectiveMode())) {
            if (fallback == null || !FALLBACKS.contains(fallback)) {
                throw invalid("FIXED_FALLBACK 缺少合法 fallback_reason");
            }
        } else if (fallback != null
                && (!"SHADOW".equals(trace.effectiveMode()) || !FALLBACKS.contains(fallback))) {
            throw invalid("fallback_reason 与 effective_mode 不匹配");
        }
        if ("FIXED".equals(trace.effectiveMode()) && trace.totalSteps() != 0) {
            throw invalid("FIXED 模式不得包含工具调用");
        }
        for (int index = 0; index < trace.toolInvocations().size(); index++) {
            AiTutorToolInvocation invocation = trace.toolInvocations().get(index);
            if (invocation == null || invocation.step() == null || invocation.step() != index + 1
                    || invocation.tool() == null || !TOOLS.contains(invocation.tool())
                    || invocation.reasonCode() == null
                    || !invocation.reasonCode().matches("[A-Z][A-Z0-9_]{0,79}")
                    || invocation.status() == null || !STATUSES.contains(invocation.status())
                    || invocation.durationMs() == null || invocation.durationMs() < 0
                    || invocation.itemCount() == null || invocation.itemCount() < 0
                    || invocation.itemCount() > 1000) {
                throw invalid("agent_trace 工具调用摘要不合法");
            }
            String error = trim(invocation.errorCode());
            if (error != null && (error.length() > 80 || !error.matches("[A-Z][A-Z0-9_]{0,79}"))
                    || "SUCCEEDED".equals(invocation.status()) && error != null
                    || !"SUCCEEDED".equals(invocation.status()) && error == null) {
                throw invalid("agent_trace error_code 与工具状态不匹配");
            }
        }
    }

    public Map<String, Object> auditSummary(AiTutorAgentTrace trace) {
        List<Map<String, Object>> tools = trace.toolInvocations().stream()
                .map(value -> {
                    Map<String, Object> summary = new LinkedHashMap<>();
                    summary.put("step", value.step());
                    summary.put("tool", value.tool());
                    summary.put("reasonCode", value.reasonCode());
                    summary.put("status", value.status());
                    summary.put("durationMs", value.durationMs());
                    summary.put("itemCount", value.itemCount());
                    if (value.errorCode() != null) summary.put("errorCode", value.errorCode());
                    return Map.copyOf(summary);
                })
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requestedMode", trace.requestedMode());
        result.put("effectiveMode", trace.effectiveMode());
        result.put("totalSteps", trace.totalSteps());
        result.put("tools", tools);
        if (trace.fallbackReason() != null) result.put("fallbackReason", trace.fallbackReason());
        return Map.copyOf(result);
    }

    private String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private BusinessException invalid(String detail) {
        return new BusinessException(ErrorCode.AI_SERVICE_INVALID_RESPONSE,
                "AI 导师返回的数据未通过业务校验", Map.of("reason", detail));
    }
}
