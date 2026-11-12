package com.education.agent.common.web;

import org.slf4j.MDC;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.UUID;

public final class RequestIds {

    public static final String ATTRIBUTE = RequestIds.class.getName() + ".requestId";

    private RequestIds() {
    }

    public static String current() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            Object value = attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
            if (value instanceof String requestId) {
                return requestId;
            }
        }
        String mdcRequestId = MDC.get("requestId");
        if (mdcRequestId != null && !mdcRequestId.isBlank()) {
            return mdcRequestId;
        }
        return newId();
    }

    public static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
