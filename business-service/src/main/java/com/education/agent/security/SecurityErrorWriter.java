package com.education.agent.security;

import com.education.agent.common.api.ErrorResponse;
import com.education.agent.common.web.RequestIds;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
public class SecurityErrorWriter {

    private final ObjectMapper objectMapper;

    public SecurityErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsetsHolder.UTF_8);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), new ErrorResponse(
                RequestIds.current(),
                code,
                message,
                Map.of(),
                List.of(),
                Instant.now()
        ));
    }

    private static final class StandardCharsetsHolder {
        private static final String UTF_8 = "UTF-8";
    }
}
