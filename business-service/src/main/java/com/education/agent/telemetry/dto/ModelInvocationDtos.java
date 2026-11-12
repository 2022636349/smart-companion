package com.education.agent.telemetry.dto;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;
import java.time.*;
public final class ModelInvocationDtos {
 private ModelInvocationDtos(){}
 public enum Outcome { SUCCESS, FAILED }
 public record ModelInvocationRequest(
  @JsonProperty("invocation_id") @NotBlank @Size(min=16,max=64) @Pattern(regexp="^[A-Za-z0-9._:-]+$") String invocationId,
  @JsonProperty("request_id") @NotBlank @Size(min=16,max=64) String requestId,
  @NotBlank @Size(max=60) String capability,@NotBlank @Size(max=40) String provider,@NotBlank @Size(max=100) String model,
  @JsonProperty("prompt_version") @NotBlank @Size(max=100) String promptVersion,
  @JsonProperty("duration_ms") @NotNull @Min(0) @Max(2147483647L) Integer durationMs,
  @JsonProperty("input_units") @NotNull @Min(0) @Max(2147483647L) Integer inputUnits,
  @JsonProperty("output_units") @NotNull @Min(0) @Max(2147483647L) Integer outputUnits,
  @NotNull @Min(1) @Max(10) Integer attempts,
  @JsonProperty("provider_request_id") @Size(max=200) String providerRequestId,
  @NotNull Outcome outcome,@JsonProperty("error_code") @Size(max=80) String errorCode,
  @JsonProperty("pricing_version") @NotBlank @Size(max=40) String pricingVersion,
  @JsonProperty("estimated_cost_microunits") @Min(0) Long estimatedCostMicrounits,
  @Pattern(regexp="^[A-Z]{3}$") String currency,
  @JsonProperty("occurred_at") @NotNull OffsetDateTime occurredAt){}
 public record ModelInvocationAcceptedResponse(
  @JsonProperty("invocation_id") String invocationId,boolean accepted,boolean duplicate,Instant timestamp){}
}
