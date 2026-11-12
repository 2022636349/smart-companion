package com.education.agent.telemetry.controller;

import com.education.agent.common.error.BusinessException;
import com.education.agent.common.web.RequestIds;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = ModelInvocationController.class)
public class ModelInvocationErrorHandler {
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String,Object>> business(BusinessException exception) {
        Map<String,Object> detail = Map.of(
                "code", exception.errorCode().name(),
                "owner", "B",
                "message", exception.getMessage(),
                "request_id", RequestIds.current());
        return ResponseEntity.status(exception.errorCode().status()).body(Map.of("detail", detail));
    }
}
