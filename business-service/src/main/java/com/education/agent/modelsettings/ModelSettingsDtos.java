package com.education.agent.modelsettings;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.List;

public final class ModelSettingsDtos {
    private ModelSettingsDtos() {}
    public enum Slot { DEFAULT, ANIMATION }
    public enum ConnectionMode { REMOTE, LOCAL, INHERIT }
    public record Settings(
        @NotBlank @Pattern(regexp="deepseek|openai_compatible|ollama|inherit") String provider,
        @NotNull @Size(max=2000) String baseUrl,
        @NotNull @Size(max=100) String model,
        @NotNull @Min(1) @Max(60) Integer timeoutSeconds,
        @NotNull @Min(0) @Max(2) Integer maxRetries,
        @NotBlank @Pattern(regexp="json_object|json_schema|text") String responseFormat,
        @NotBlank @Pattern(regexp="max_tokens|max_completion_tokens") String tokenLimitField,
        @NotNull Boolean sendTemperature
    ) {}
    public record Update(@NotNull @Min(0) Long expectedVersion, @NotNull @Valid Settings settings,
                         @Size(max=4096) String apiKey) {
        @Override public String toString() { return "ModelSettingsUpdate[redacted]"; }
    }
    public record RuntimeProfile(Slot slot, long version, Settings settings, String apiKey, boolean useEnvironmentKey) {
        @Override public String toString() { return "ModelRuntimeProfile[redacted]"; }
    }
    public record Deployment(Slot slot, Settings settings, boolean apiKeyConfigured, boolean runtimeEnabled) {}
    public record SavedConnection(ConnectionMode mode, Settings settings, boolean apiKeyConfigured) {}
    public record View(Slot slot, long version, String source, Settings settings, boolean apiKeyConfigured,
                       LocalDateTime updatedAt, boolean runtimeEnabled, List<SavedConnection> savedConnections) {}
    public record TestResult(boolean success, String code, String message, long latencyMs) {}
    public record CatalogRequest(@NotNull @Min(0) Long expectedVersion, @NotBlank @Size(max=2000) String baseUrl) {}
    public record InstalledModel(String name, long sizeBytes, String parameterSize, String quantization) {}
    public record CatalogResult(boolean success, String code, String message, List<InstalledModel> models, long latencyMs) {}
}
