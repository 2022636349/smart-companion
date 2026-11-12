package com.education.agent.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI implementedApiDocumentation() {
        String schemeName = "bearerAuth";
        return new OpenAPI()
                .info(new Info()
                        .title("智学引擎 Spring Boot 已实现接口")
                        .version("2.0.0-week1")
                        .description("仅展示当前已实现路由；完整目标合同以 docs/openapi/education-public-api-v2.yaml 为准。"))
                .servers(java.util.List.of(new Server().url("http://localhost:8080")))
                .components(new Components().addSecuritySchemes(
                        schemeName,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                ))
                .addSecurityItem(new SecurityRequirement().addList(schemeName));
    }
}
