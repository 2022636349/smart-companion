package com.education.agent.modelsettings;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import com.education.agent.common.error.BusinessException;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ModelSettingsClientTest {
    private ModelSettingsClient client;
    private MockRestServiceServer server;
    @BeforeEach void setup() {
        RestClient.Builder builder=RestClient.builder().baseUrl("http://ai.test");
        server=MockRestServiceServer.bindTo(builder).build();
        client=new ModelSettingsClient(builder.build(),"synthetic-internal-token");
    }
    @Test void sendsOnlyAddressWithInternalAuthAndReturnsValidatedModels() {
        server.expect(requestTo("http://ai.test/internal/models/settings/models")).andExpect(method(HttpMethod.POST))
            .andExpect(header("X-Internal-Token","synthetic-internal-token"))
            .andExpect(content().json("{\"baseUrl\":\"http://host.docker.internal:11434\"}"))
            .andRespond(withSuccess("{\"success\":true,\"code\":\"MODELS_AVAILABLE\",\"message\":\"ready\",\"models\":[{\"name\":\"local:3b\",\"sizeBytes\":1000,\"parameterSize\":\"3B\",\"quantization\":\"Q4\"}],\"latencyMs\":4}",MediaType.APPLICATION_JSON));
        assertThat(client.models("http://host.docker.internal:11434").models().get(0).name()).isEqualTo("local:3b");
        server.verify();
    }
    @Test void doesNotInventModelsForExplicitConnectionFailure() {
        server.expect(anything()).andRespond(withSuccess("{\"success\":false,\"code\":\"OLLAMA_UNREACHABLE\",\"message\":\"unreachable\",\"models\":[],\"latencyMs\":2}",MediaType.APPLICATION_JSON));
        assertThat(client.models("http://localhost:11434").success()).isFalse();
    }
    @Test void rejectsMalformedUpstreamCatalog() {
        server.expect(anything()).andRespond(withSuccess("{\"success\":true,\"code\":\"MODELS_AVAILABLE\",\"message\":\"bad\",\"models\":[],\"latencyMs\":2}",MediaType.APPLICATION_JSON));
        assertThatThrownBy(()->client.models("http://localhost:11434")).isInstanceOf(BusinessException.class);
    }
    @Test void deploymentStillRequiresBothSlotsAndInternalAuthentication() {
        server.expect(requestTo("http://ai.test/internal/models/settings/deployment"))
            .andExpect(header("X-Internal-Token","synthetic-internal-token"))
            .andRespond(withSuccess("[]",MediaType.APPLICATION_JSON));
        assertThatThrownBy(client::deployment).isInstanceOf(BusinessException.class);
    }
    @Test void invalidConfigurationResponseRemainsAValidationError() {
        server.expect(requestTo("http://ai.test/internal/models/settings/validate"))
            .andExpect(header("X-Internal-Token","synthetic-internal-token"))
            .andRespond(withStatus(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY).body("private-upstream-detail"));
        var profile=new ModelSettingsDtos.RuntimeProfile(ModelSettingsDtos.Slot.DEFAULT,1,
            new ModelSettingsDtos.Settings("ollama","http://localhost:11434","local:3b",30,0,"json_object","max_tokens",true),null,false);
        assertThatThrownBy(()->client.check(profile,false)).isInstanceOf(BusinessException.class).hasMessageNotContaining("private-upstream-detail");
    }
}
