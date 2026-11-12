package com.education.agent.auth.dto;

import com.education.agent.user.dto.UserView;

public record AuthData(
        UserView user,
        AuthTokens tokens
) {
}
