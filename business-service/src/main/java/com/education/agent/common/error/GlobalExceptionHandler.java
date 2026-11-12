package com.education.agent.common.error;

import com.education.agent.common.api.ErrorResponse;
import com.education.agent.common.api.FieldErrorData;
import com.education.agent.common.web.RequestIds;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import com.education.agent.ratelimit.RateLimitException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException exception) {
        ErrorCode code = exception.errorCode();
        return ResponseEntity.status(code.status()).body(error(
                code.name(),
                exception.getMessage(),
                exception.details(),
                List.of()
        ));
    }

    @ExceptionHandler(RateLimitException.class)
    public ResponseEntity<ErrorResponse> handleRateLimit(RateLimitException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", Long.toString(exception.retryAfterSeconds()))
                .body(error(ErrorCode.RATE_LIMITED.name(), exception.getMessage(),
                        Map.of("retryAfterSeconds", exception.retryAfterSeconds()), List.of()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResponseEntity<ErrorResponse> handleBinding(Exception exception) {
        var bindingResult = exception instanceof MethodArgumentNotValidException methodException
                ? methodException.getBindingResult()
                : ((BindException) exception).getBindingResult();
        List<FieldErrorData> fieldErrors = bindingResult.getFieldErrors().stream()
                .map(fieldError -> new FieldErrorData(
                        fieldError.getField(),
                        fieldError.getCode() == null ? "INVALID" : fieldError.getCode(),
                        fieldError.getDefaultMessage() == null ? "字段不合法" : fieldError.getDefaultMessage()
                ))
                .toList();
        return ResponseEntity.unprocessableEntity().body(error(
                ErrorCode.VALIDATION_ERROR.name(),
                "提交的数据不符合要求",
                Map.of(),
                fieldErrors
        ));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException exception) {
        List<FieldErrorData> fieldErrors = exception.getConstraintViolations().stream()
                .map(violation -> new FieldErrorData(
                        violation.getPropertyPath().toString(),
                        "INVALID",
                        violation.getMessage()
                ))
                .toList();
        return ResponseEntity.unprocessableEntity().body(error(
                ErrorCode.VALIDATION_ERROR.name(),
                "提交的数据不符合要求",
                Map.of(),
                fieldErrors
        ));
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class,
            MissingRequestHeaderException.class,
            MissingServletRequestPartException.class
    })
    public ResponseEntity<ErrorResponse> handleMalformedInput(Exception exception) {
        return ResponseEntity.unprocessableEntity().body(error(
                ErrorCode.VALIDATION_ERROR.name(),
                "请求格式、参数类型或枚举值不正确",
                Map.of("exception", exception.getClass().getSimpleName()),
                List.of()
        ));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSize(MaxUploadSizeExceededException exception) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(error(
                ErrorCode.FILE_TOO_LARGE.name(),
                "文件超过允许大小",
                Map.of(),
                List.of()
        ));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoResourceFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error(
                ErrorCode.RESOURCE_NOT_FOUND.name(),
                "接口或资源不存在",
                Map.of(),
                List.of()
        ));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException exception) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(error(
                "METHOD_NOT_ALLOWED",
                "请求方法不允许",
                Map.of("method", exception.getMethod()),
                List.of()
        ));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException exception) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(error(
                "UNSUPPORTED_MEDIA_TYPE",
                "请求内容类型不支持",
                Map.of(),
                List.of()
        ));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleConflict(DataIntegrityViolationException exception) {
        log.warn("Database constraint conflict: {}", exception.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error(
                ErrorCode.CONCURRENT_MODIFICATION.name(),
                "数据已被修改或违反唯一性约束",
                Map.of(),
                List.of()
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception exception) {
        log.error("Unhandled request failure", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error(
                ErrorCode.INTERNAL_ERROR.name(),
                "服务器内部错误",
                Map.of(),
                List.of()
        ));
    }

    private ErrorResponse error(
            String code,
            String message,
            Map<String, Object> details,
            List<FieldErrorData> fieldErrors
    ) {
        return new ErrorResponse(
                RequestIds.current(),
                code,
                message,
                details,
                fieldErrors,
                Instant.now()
        );
    }
}
