package com.education.agent.tutor.dto;

import com.education.agent.tutor.model.MasteryStatus;

import java.math.BigDecimal;

public record CognitiveLabelView(
        String knowledgePointId,
        MasteryStatus status,
        BigDecimal confidence,
        String evidence
) {
}
