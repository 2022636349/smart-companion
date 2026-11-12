package com.education.agent.modelsettings;

import static com.education.agent.modelsettings.ModelSettingsDtos.*;
import com.education.agent.common.error.*;
import com.education.agent.common.web.RequestIds;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

@Component
public class ModelSettingsClient {
    private final RestClient client;
    private final String token;
    public ModelSettingsClient(@Qualifier("aiAssessmentRestClient") RestClient client,
            @Value("${education.ai-service.internal-token:}") String token) { this.client=client; this.token=token; }
    private String requestId() { String id=RequestIds.current(); return id == null ? "req-" + UUID.randomUUID() : id; }
    public List<Deployment> deployment() {
        try {
            Deployment[] result = client.get().uri("/internal/models/settings/deployment")
                .header("X-Internal-Token", token).header("X-Request-Id",requestId()).retrieve().body(Deployment[].class);
            if (result == null || result.length != 2 || Arrays.stream(result).map(Deployment::slot).distinct().count()!=2)
                throw unavailable();
            return List.of(result);
        } catch (RestClientException ignored) { throw unavailable(); }
    }
    public TestResult check(RuntimeProfile profile, boolean test) {
        try {
            TestResult result=client.post().uri("/internal/models/settings/"+(test?"test":"validate"))
                .header("X-Internal-Token", token).header("X-Request-Id",requestId()).body(profile)
                .retrieve().body(TestResult.class);
            if(result==null) throw unavailable();
            return result;
        } catch (HttpClientErrorException.UnprocessableEntity ignored) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,"请检查模型、地址与密钥；更换服务地址后需重新输入密钥。");
        } catch (RestClientException ignored) { throw unavailable(); }
    }
    public CatalogResult models(String baseUrl) {
        try {
            CatalogResult result=client.post().uri("/internal/models/settings/models")
                .header("X-Internal-Token",token).header("X-Request-Id",requestId()).body(Map.of("baseUrl",baseUrl))
                .retrieve().body(CatalogResult.class);
            if(result==null || result.models()==null || result.models().size()>200 || result.code()==null
                    || result.code().length()>100 || result.message()==null || result.message().length()>500 || result.latencyMs()<0
                    || (!result.success() && !result.models().isEmpty()) || (result.success() && result.models().isEmpty())
                    || result.models().stream().anyMatch(m->m==null || m.name()==null || m.name().isBlank() || m.name().length()>100
                        || m.sizeBytes()<0 || m.parameterSize()==null || m.parameterSize().length()>100
                        || m.quantization()==null || m.quantization().length()>100)
                    || result.models().stream().map(InstalledModel::name).distinct().count()!=result.models().size()) throw unavailable();
            return result;
        } catch(HttpClientErrorException.UnprocessableEntity ignored) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,"请检查 Ollama 服务地址。");
        } catch(RestClientException ignored) { throw unavailable(); }
    }
    private BusinessException unavailable() { return new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE,"模型配置服务暂时不可用，请稍后重试。"); }
}
