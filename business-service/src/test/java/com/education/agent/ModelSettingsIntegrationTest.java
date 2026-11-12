package com.education.agent;

import com.education.agent.modelsettings.*;
import static com.education.agent.modelsettings.ModelSettingsDtos.*;
import com.education.agent.common.error.*;
import com.education.agent.security.AuthenticatedUser;
import com.education.agent.user.entity.UserEntity;
import com.education.agent.user.mapper.UserMapper;
import com.education.agent.user.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @Transactional
class ModelSettingsIntegrationTest {
    @Autowired MockMvc mvc; @Autowired ModelSettingsService service; @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json; @Autowired UserMapper users; @Autowired PasswordEncoder passwords;
    @MockBean ModelSettingsClient client;
    private Settings settings = new Settings("deepseek","https://api.deepseek.com","fixture-model",30,1,"json_object","max_tokens",true);
    private AuthenticatedUser admin;
    private String token;
    @BeforeEach void setup() throws Exception {
        UserEntity u = new UserEntity();u.setUsername("model_admin_"+UUID.randomUUID().toString().substring(0,8));
        u.setPasswordHash(passwords.encode("FixturePass@123"));u.setDisplayName("配置管理员");u.setRole(UserRole.ADMIN);u.setStatus(UserStatus.ACTIVE);u.setForcePasswordChange(false);users.insert(u);
        admin=AuthenticatedUser.from(u);
        token=json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("username",u.getUsername(),"password","FixturePass@123"))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).at("/data/tokens/accessToken").asText();
        when(client.deployment()).thenReturn(List.of(new Deployment(Slot.DEFAULT,settings,true,true),new Deployment(Slot.ANIMATION,new Settings("inherit","","",45,1,"json_object","max_tokens",false),false,true)));
        when(client.check(any(),anyBoolean())).thenReturn(new TestResult(true,"VALID","通过",0));
    }
    @Test void saveEncryptsCredentialsPublicReadNeverReturnsThemAndResetRestoresDeployment() throws Exception {
        var request = new Update(0L,settings,"synthetic-do-not-return-key");
        String output=mvc.perform(put("/api/admin/model-settings/DEFAULT").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.data.version").value(1)).andExpect(jsonPath("$.data.source").value("ADMIN"))
            .andExpect(jsonPath("$.data.apiKeyConfigured").value(true)).andReturn().getResponse().getContentAsString();
        assertThat(output).doesNotContain("synthetic-do-not-return-key").doesNotContain("useEnvironmentKey");
        String stored=db.queryForObject("SELECT encrypted_config FROM edu_model_settings WHERE slot='DEFAULT'",String.class);
        assertThat(stored).doesNotContain("synthetic-do-not-return-key").doesNotContain("fixture-model");
        assertThat(service.snapshot().get(0).apiKey()).isEqualTo("synthetic-do-not-return-key");
        service.save(admin,Slot.DEFAULT,new Update(1L,settings,""));
        assertThat(service.snapshot().get(0).apiKey()).isEqualTo("synthetic-do-not-return-key");
        service.reset(admin,Slot.DEFAULT,2);
        assertThat(service.snapshot()).isEmpty();
        assertThat(service.list(admin).get(0).source()).isEqualTo("DEPLOYMENT");
        assertThat(db.queryForList("SELECT details_json FROM edu_audit_log WHERE target_type='MODEL_SETTINGS'").toString()).doesNotContain("synthetic-do-not-return-key");
    }
    @Test void staleVersionAndFailedValidationDoNotReplaceSavedConfiguration() {
        service.save(admin,Slot.DEFAULT,new Update(0L,settings,"test-key"));
        assertThatThrownBy(()->service.save(admin,Slot.DEFAULT,new Update(0L,settings,"other-key"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.reset(admin,Slot.DEFAULT,0)).isInstanceOf(BusinessException.class);
        when(client.check(any(),eq(false))).thenThrow(new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE,"offline"));
        assertThatThrownBy(()->service.save(admin,Slot.DEFAULT,new Update(1L,settings,"replacement"))).isInstanceOf(BusinessException.class);
        assertThat(service.snapshot().get(0).apiKey()).isEqualTo("test-key");
        assertThat(service.list(admin).get(0).version()).isEqualTo(1);
    }
    @Test void changingEndpointRequiresNewKeyAndTestDoesNotSave() {
        Settings other=new Settings("openai_compatible","https://other.example/v1","other",30,0,"text","max_completion_tokens",false);
        assertThatThrownBy(()->service.save(admin,Slot.DEFAULT,new Update(0L,other,null))).isInstanceOf(BusinessException.class);
        service.test(admin,Slot.DEFAULT,new Update(0L,other,"new-test-key"));
        assertThat(service.snapshot()).isEmpty();
        service.save(admin,Slot.DEFAULT,new Update(0L,other,"new-test-key"));
        assertThat(service.snapshot().get(0).settings().provider()).isEqualTo("openai_compatible");
    }
    @Test void animationCanInheritWhileDefaultCannot() {
        Settings inherited=new Settings("inherit","","",45,1,"json_object","max_tokens",false);
        assertThatThrownBy(()->service.save(admin,Slot.DEFAULT,new Update(0L,inherited,null))).isInstanceOf(BusinessException.class);
        service.save(admin,Slot.ANIMATION,new Update(0L,inherited,null));
        assertThat(service.snapshot().get(0).apiKey()).isNull();
    }
    @Test void internalSnapshotRequiresTokenAndPublicOperationsRequireAdmin() throws Exception {
        mvc.perform(get("/api/admin/model-settings")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/internal/ai/model-settings").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/internal/ai/model-settings").header("X-Internal-Token","test-internal-token").header("X-Request-Id","model-settings-internal-test"))
            .andExpect(status().isOk()).andExpect(content().json("[]"));
        UserEntity student=users.selectById(admin.id()); student.setRole(UserRole.STUDENT);users.updateById(student);
        mvc.perform(get("/api/admin/model-settings").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/model-settings/DEFAULT").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new Update(0L,settings,"x"))))
            .andExpect(status().isForbidden());
    }
    @Test void blankKeyPreservesDeploymentReferenceAndInvalidBoundsNeverReachUpstream() throws Exception {
        service.save(admin,Slot.DEFAULT,new Update(0L,settings,null));
        assertThat(service.snapshot().get(0).useEnvironmentKey()).isTrue();
        mvc.perform(put("/api/admin/model-settings/DEFAULT").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(new Update(1L,new Settings("deepseek",settings.baseUrl(),"test",999,9,"bad","max_tokens",true),"test"))))
            .andExpect(status().isUnprocessableEntity());
        assertThat(service.list(admin).get(0).version()).isEqualTo(1);
    }
    @Test void ollamaDiscoveryRequiresAdminAndVersionWithoutSavingAnyConfiguration() throws Exception {
        when(client.models("http://host.docker.internal:11434")).thenReturn(new CatalogResult(true,"MODELS_AVAILABLE","已读取模型",List.of(new InstalledModel("local:3b",2000,"3B","Q4_K_M")),5));
        mvc.perform(post("/api/admin/model-settings/DEFAULT/models").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(new CatalogRequest(0L,"http://host.docker.internal:11434/"))))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.data.models[0].name").value("local:3b"));
        assertThat(service.snapshot()).isEmpty();
        assertThatThrownBy(()->service.models(admin,Slot.DEFAULT,new CatalogRequest(1L,"http://host.docker.internal:11434"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.models(admin,Slot.DEFAULT,new CatalogRequest(0L,"http://localhost:11434/api/tags"))).isInstanceOf(BusinessException.class);
        mvc.perform(post("/api/admin/model-settings/DEFAULT/models").contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0,\"baseUrl\":\"http://localhost:11434\"}"))
            .andExpect(status().isUnauthorized());
        UserEntity student=users.selectById(admin.id());student.setRole(UserRole.STUDENT);users.updateById(student);
        mvc.perform(post("/api/admin/model-settings/DEFAULT/models").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(new CatalogRequest(0L,"http://localhost:11434"))))
            .andExpect(status().isForbidden());
    }
    @Test void localModelSavesWithoutKeyAndSnapshotKeepsNativeProvider() {
        Settings local=new Settings("ollama","http://192.168.1.23:11434","local:3b",60,0,"json_object","max_tokens",true);
        service.save(admin,Slot.DEFAULT,new Update(0L,local,"unused-remote-key"));
        RuntimeProfile saved=service.snapshot().get(0);
        assertThat(saved.settings().provider()).isEqualTo("ollama");
        assertThat(saved.settings().baseUrl()).isEqualTo(local.baseUrl());
        assertThat(saved.apiKey()).isNull();assertThat(saved.useEnvironmentKey()).isFalse();
        assertThat(service.list(admin).get(0).apiKeyConfigured()).isFalse();
        service.test(admin,Slot.DEFAULT,new Update(1L,local,null));
        assertThat(service.snapshot().get(0).version()).isEqualTo(1);
    }
    private Settings localSettings() { return new Settings("ollama","http://localhost:11434","local:3b",60,0,"json_object","max_tokens",true); }
    @Test void remoteLocalRemoteRoundTripRetainsEncryptedKeyAndPublishesOnlyActiveProfile() throws Exception {
        service.save(admin,Slot.DEFAULT,new Update(0L,settings,"retained-secret-key"));
        service.save(admin,Slot.DEFAULT,new Update(1L,localSettings(),null));
        // Independent list/read after saving models a page reload.
        View read=service.list(admin).get(0);
        assertThat(read.settings().provider()).isEqualTo("ollama");
        SavedConnection remote=read.savedConnections().stream().filter(s->s.mode()==ConnectionMode.REMOTE).findFirst().orElseThrow();
        assertThat(remote.apiKeyConfigured()).isTrue();assertThat(remote.settings()).isEqualTo(settings);
        assertThat(json.writeValueAsString(read)).doesNotContain("retained-secret-key","useEnvironmentKey");
        String encrypted=db.queryForObject("SELECT encrypted_connections FROM edu_model_settings WHERE slot='DEFAULT'",String.class);
        assertThat(encrypted).doesNotContain("retained-secret-key","fixture-model","localhost");
        assertThat(service.snapshot()).hasSize(1);
        assertThat(service.snapshot().get(0).apiKey()).isNull();
        service.test(admin,Slot.DEFAULT,new Update(2L,remote.settings(),null));
        assertThat(service.snapshot().get(0).settings().provider()).isEqualTo("ollama");
        service.save(admin,Slot.DEFAULT,new Update(2L,remote.settings(),null));
        assertThat(service.snapshot().get(0).apiKey()).isEqualTo("retained-secret-key");
        assertThat(service.list(admin).get(0).savedConnections()).anyMatch(s->s.mode()==ConnectionMode.LOCAL && s.settings().model().equals("local:3b"));
        service.save(admin,Slot.DEFAULT,new Update(3L,localSettings(),null));
        service.save(admin,Slot.DEFAULT,new Update(4L,settings,null));
        assertThat(service.snapshot().get(0).apiKey()).isEqualTo("retained-secret-key");
    }
    @Test void legacyCurrentConfigIsCapturedAndResetDoesNotDeleteRetainedKey() throws Exception {
        RuntimeProfile legacy=new RuntimeProfile(Slot.DEFAULT,4,settings,"legacy-retained-key",false);
        // A V16 row starts with no retained field; its existing ciphertext remains valid.
        String encrypted=applicationCipher.encrypt("DEFAULT",json.writeValueAsString(legacy));
        db.update("UPDATE edu_model_settings SET encrypted_config=?,version_no=4,encrypted_connections=NULL WHERE slot='DEFAULT'",encrypted);
        service.save(admin,Slot.DEFAULT,new Update(4L,localSettings(),null));
        service.reset(admin,Slot.DEFAULT,5L);
        assertThat(service.snapshot()).isEmpty();
        assertThat(service.list(admin).get(0).savedConnections()).anyMatch(s->s.mode()==ConnectionMode.REMOTE && s.apiKeyConfigured());
        service.save(admin,Slot.DEFAULT,new Update(6L,settings,null));
        assertThat(service.snapshot().get(0).apiKey()).isEqualTo("legacy-retained-key");
    }
    @Autowired ModelSettingsCipher applicationCipher;
    @Test void failedAndConcurrentSavesLeaveRetainedConnectionsUntouched() {
        service.save(admin,Slot.DEFAULT,new Update(0L,settings,"remote-preserved-key"));
        String before=db.queryForObject("SELECT encrypted_connections FROM edu_model_settings WHERE slot='DEFAULT'",String.class);
        assertThatThrownBy(()->service.save(admin,Slot.DEFAULT,new Update(0L,localSettings(),null))).isInstanceOf(BusinessException.class);
        when(client.check(any(),eq(false))).thenThrow(new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE,"offline"));
        assertThatThrownBy(()->service.save(admin,Slot.DEFAULT,new Update(1L,localSettings(),null))).isInstanceOf(BusinessException.class);
        assertThat(db.queryForObject("SELECT encrypted_connections FROM edu_model_settings WHERE slot='DEFAULT'",String.class)).isEqualTo(before);
        assertThat(service.snapshot().get(0).apiKey()).isEqualTo("remote-preserved-key");
    }
    @Test void retainedCredentialsCannotCrossSlotsProvidersOrAddresses() {
        service.save(admin,Slot.DEFAULT,new Update(0L,settings,"default-only-key"));
        service.save(admin,Slot.DEFAULT,new Update(1L,localSettings(),null));
        Settings other=new Settings("deepseek","https://other.example/v1","other",30,0,"json_object","max_tokens",true);
        assertThatThrownBy(()->service.save(admin,Slot.DEFAULT,new Update(2L,other,null))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.save(admin,Slot.ANIMATION,new Update(0L,settings,null))).isInstanceOf(BusinessException.class);
        service.save(admin,Slot.ANIMATION,new Update(0L,settings,"animation-only-key"));
        service.save(admin,Slot.ANIMATION,new Update(1L,new Settings("inherit","","",45,1,"json_object","max_tokens",false),null));
        service.save(admin,Slot.ANIMATION,new Update(2L,settings,null));
        assertThat(service.snapshot().stream().filter(p->p.slot()==Slot.ANIMATION).findFirst().orElseThrow().apiKey()).isEqualTo("animation-only-key");
        assertThat(service.snapshot().stream().filter(p->p.slot()==Slot.DEFAULT).findFirst().orElseThrow().apiKey()).isNull();
    }
    @Test void environmentCredentialReferenceSurvivesLocalSwitchWithoutCopyingASecret() {
        service.save(admin,Slot.DEFAULT,new Update(0L,localSettings(),null));
        service.save(admin,Slot.DEFAULT,new Update(1L,settings,null));
        RuntimeProfile active=service.snapshot().get(0);
        assertThat(active.useEnvironmentKey()).isTrue();assertThat(active.apiKey()).isNull();
    }
}
