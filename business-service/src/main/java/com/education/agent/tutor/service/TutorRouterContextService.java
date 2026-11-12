package com.education.agent.tutor.service;

import com.education.agent.common.error.BusinessException;
import com.education.agent.common.error.ErrorCode;
import com.education.agent.integration.ai.AiTutorMasteryInput;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Builds the only mastery context accepted by the tutor router: a bounded,
 * server-configured projection of the current student's MySQL source of truth.
 */
@Service
public class TutorRouterContextService {
    private static final int MAX_MASTERY = 100;
    private static final Set<String> MODES = Set.of("FIXED", "ROUTER", "SHADOW");

    private final JdbcTemplate jdbc;
    private final String agentMode;

    public TutorRouterContextService(
            JdbcTemplate jdbc,
            @Value("${education.tutor.agent-mode:FIXED}") String configuredMode) {
        this.jdbc = jdbc;
        String normalized = configuredMode == null ? "" : configuredMode.trim().toUpperCase(Locale.ROOT);
        if (!MODES.contains(normalized)) {
            throw new IllegalArgumentException(
                    "education.tutor.agent-mode must be FIXED, ROUTER, or SHADOW");
        }
        this.agentMode = normalized;
    }

    public RouterContext forStudent(Long studentId, Long courseId, String currentKnowledgePointId) {
        if ("FIXED".equals(agentMode)) return new RouterContext(agentMode, List.of());
        List<AiTutorMasteryInput> values = jdbc.query("""
                SELECT knowledge_point_id,score,confidence
                FROM edu_knowledge_mastery
                WHERE student_id=? AND course_id=?
                ORDER BY CASE WHEN knowledge_point_id=? THEN 0 ELSE 1 END,
                         updated_at DESC,knowledge_point_id
                LIMIT 100
                """, (rs, row) -> new AiTutorMasteryInput(
                rs.getString("knowledge_point_id"),
                rs.getBigDecimal("score"),
                rs.getBigDecimal("confidence")), studentId, courseId, currentKnowledgePointId);
        validate(values);
        return new RouterContext(agentMode, values);
    }

    public String agentMode() {
        return agentMode;
    }

    private void validate(List<AiTutorMasteryInput> values) {
        if (values == null || values.size() > MAX_MASTERY) throw invalidMastery();
        Set<String> ids = new HashSet<>();
        for (AiTutorMasteryInput value : values) {
            if (value == null || value.knowledgePointId() == null
                    || value.knowledgePointId().isBlank() || value.knowledgePointId().length() > 64
                    || !ids.add(value.knowledgePointId())
                    || outside(value.score(), BigDecimal.ZERO, new BigDecimal("100"))
                    || outside(value.confidence(), BigDecimal.ZERO, BigDecimal.ONE)) {
                throw invalidMastery();
            }
        }
    }

    private boolean outside(BigDecimal value, BigDecimal min, BigDecimal max) {
        return value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0;
    }

    private BusinessException invalidMastery() {
        return new BusinessException(ErrorCode.INTERNAL_ERROR, "学生掌握度权威数据不符合 Router 边界");
    }

    public record RouterContext(String agentMode, List<AiTutorMasteryInput> masteryContext) {
        public RouterContext {
            masteryContext = List.copyOf(masteryContext);
        }
    }
}
