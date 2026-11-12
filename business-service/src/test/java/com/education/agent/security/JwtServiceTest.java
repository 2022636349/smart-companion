package com.education.agent.security;

import com.education.agent.user.entity.UserEntity;
import com.education.agent.user.model.UserRole;
import com.education.agent.user.model.UserStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    @Test
    void issuesAndVerifiesHs256AccessTokenAndRejectsTampering() {
        JwtService service = new JwtService(
                new ObjectMapper(),
                "unit-test-secret-with-more-than-thirty-two-characters",
                300
        );
        UserEntity user = new UserEntity();
        user.setId(42L);
        user.setUsername("jwt_student");
        user.setRole(UserRole.STUDENT);
        user.setStatus(UserStatus.ACTIVE);

        String token = service.issueAccessToken(user);
        JwtClaims claims = service.parseAndVerify(token);

        assertThat(claims.userId()).isEqualTo(42L);
        assertThat(claims.username()).isEqualTo("jwt_student");
        assertThat(claims.role()).isEqualTo(UserRole.STUDENT);

        String tampered = token.substring(0, token.length() - 1)
                + (token.endsWith("a") ? "b" : "a");
        assertThatThrownBy(() -> service.parseAndVerify(tampered))
                .isInstanceOf(JwtService.JwtValidationException.class);
    }
}
