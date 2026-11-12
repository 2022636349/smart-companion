package com.education.agent.security;

import com.education.agent.user.entity.UserEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class JwtService {

    private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final byte[] signingKey;
    private final long accessTokenSeconds;

    public JwtService(
            ObjectMapper objectMapper,
            @Value("${education.security.jwt-secret}") String secret,
            @Value("${education.security.access-token-seconds:1800}") long accessTokenSeconds
    ) {
        this.objectMapper = objectMapper;
        this.signingKey = sha256(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenSeconds = accessTokenSeconds;
    }

    public String issueAccessToken(UserEntity user) {
        Instant now = Instant.now();
        Map<String, Object> header = Map.of("alg", "HS256", "typ", "JWT");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", user.getId().toString());
        payload.put("username", user.getUsername());
        payload.put("role", user.getRole().name());
        payload.put("iat", now.getEpochSecond());
        payload.put("exp", now.plusSeconds(accessTokenSeconds).getEpochSecond());
        payload.put("jti", UUID.randomUUID().toString());

        String signingInput = encodeJson(header) + "." + encodeJson(payload);
        return signingInput + "." + BASE64_URL_ENCODER.encodeToString(sign(signingInput));
    }

    public JwtClaims parseAndVerify(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                throw new JwtValidationException("JWT 格式不正确");
            }
            String signingInput = parts[0] + "." + parts[1];
            byte[] expected = sign(signingInput);
            byte[] actual = BASE64_URL_DECODER.decode(parts[2]);
            // Reject alternate Base64URL spellings that differ only in unused
            // trailing bits. JWT signatures must use the canonical unpadded form.
            if (!BASE64_URL_ENCODER.encodeToString(actual).equals(parts[2])) {
                throw new JwtValidationException("JWT 签名编码不规范");
            }
            if (!MessageDigest.isEqual(expected, actual)) {
                throw new JwtValidationException("JWT 签名无效");
            }

            Map<String, Object> payload = objectMapper.readValue(
                    BASE64_URL_DECODER.decode(parts[1]),
                    MAP_TYPE
            );
            Instant issuedAt = Instant.ofEpochSecond(number(payload, "iat"));
            Instant expiresAt = Instant.ofEpochSecond(number(payload, "exp"));
            if (!expiresAt.isAfter(Instant.now())) {
                throw new JwtValidationException("JWT 已过期");
            }
            return new JwtClaims(
                    Long.valueOf(requiredText(payload, "sub")),
                    requiredText(payload, "username"),
                    com.education.agent.user.model.UserRole.valueOf(requiredText(payload, "role")),
                    issuedAt,
                    expiresAt,
                    requiredText(payload, "jti")
            );
        } catch (JwtValidationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new JwtValidationException("JWT 无法解析", exception);
        }
    }

    public long accessTokenSeconds() {
        return accessTokenSeconds;
    }

    private String encodeJson(Map<String, Object> value) {
        try {
            return BASE64_URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(value));
        } catch (Exception exception) {
            throw new IllegalStateException("JWT 序列化失败", exception);
        }
    }

    private byte[] sign(String signingInput) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(signingKey, HMAC_ALGORITHM));
            return mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("JWT 签名失败", exception);
        }
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private static String requiredText(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new JwtValidationException("JWT 缺少字段 " + key);
        }
        return value.toString();
    }

    private static long number(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new JwtValidationException("JWT 字段类型错误 " + key);
    }

    public static final class JwtValidationException extends RuntimeException {
        public JwtValidationException(String message) {
            super(message);
        }

        public JwtValidationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
