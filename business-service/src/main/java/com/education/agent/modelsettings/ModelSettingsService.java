package com.education.agent.modelsettings;

import static com.education.agent.modelsettings.ModelSettingsDtos.*;
import com.education.agent.admin.service.AuditLogService;
import com.education.agent.common.error.*;
import com.education.agent.security.AuthenticatedUser;
import com.education.agent.user.model.UserRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ModelSettingsService {
    private record Row(Slot slot, long version, String encrypted, LocalDateTime updated, String connections) {}
    private record RetainedConnections(Map<ConnectionMode,RuntimeProfile> profiles) {}
    private final JdbcTemplate db; private final ModelSettingsClient client; private final ModelSettingsCipher cipher;
    private final ObjectMapper json; private final TransactionTemplate tx; private final AuditLogService audit;
    public ModelSettingsService(JdbcTemplate db, ModelSettingsClient client, ModelSettingsCipher cipher,
            ObjectMapper json, PlatformTransactionManager manager, AuditLogService audit) {
        this.db=db;this.client=client;this.cipher=cipher;this.json=json;this.tx=new TransactionTemplate(manager);this.audit=audit;
    }
    private void admin(AuthenticatedUser user) { if(user==null || user.role()!=UserRole.ADMIN) throw new BusinessException(ErrorCode.FORBIDDEN,"仅管理员可修改模型配置。"); }
    private List<Row> rows() {
        return db.query("SELECT slot,version_no,encrypted_config,updated_at,encrypted_connections FROM edu_model_settings ORDER BY slot",
            (rs,n)-> new Row(Slot.valueOf(rs.getString(1)),rs.getLong(2),rs.getString(3),rs.getTimestamp(4)==null?null:rs.getTimestamp(4).toLocalDateTime(),rs.getString(5)));
    }
    private Row row(Slot slot) { return rows().stream().filter(r->r.slot()==slot).findFirst().orElseThrow(()->new BusinessException(ErrorCode.INTERNAL_ERROR,"模型配置表尚未初始化。")); }
    private RuntimeProfile decode(Row row) {
        if(row.encrypted()==null) return null;
        try { return json.readValue(cipher.decrypt(row.slot().name(),row.encrypted()),RuntimeProfile.class); }
        catch (BusinessException e) { throw e; }
        catch (Exception ignored) { throw new BusinessException(ErrorCode.INTERNAL_ERROR,"模型配置无法读取。"); }
    }
    private Deployment deployment(Slot slot) { return client.deployment().stream().filter(d->d.slot()==slot).findFirst().orElseThrow(); }
    private ConnectionMode mode(Settings settings) {
        return settings.provider().equals("ollama")?ConnectionMode.LOCAL:settings.provider().equals("inherit")?ConnectionMode.INHERIT:ConnectionMode.REMOTE;
    }
    private boolean sameEndpoint(Settings a,Settings b) {
        return a.provider().equals(b.provider()) && a.baseUrl().replaceAll("/+$", "").equals(b.baseUrl().replaceAll("/+$", ""));
    }
    private RuntimeProfile deploymentProfile(Row row,Deployment d) {
        return new RuntimeProfile(row.slot(),row.version(),d.settings(),null,mode(d.settings())==ConnectionMode.REMOTE);
    }
    private Map<ConnectionMode,RuntimeProfile> connections(Row row,Deployment d) {
        Map<ConnectionMode,RuntimeProfile> profiles=new EnumMap<>(ConnectionMode.class);
        if(row.connections()!=null) {
            try { profiles.putAll(json.readValue(cipher.decrypt(row.slot().name()+":connections",row.connections()),RetainedConnections.class).profiles()); }
            catch(BusinessException e){throw e;} catch(Exception ignored){throw invalid("已保留的模型配置无法读取。");}
        }
        RuntimeProfile active=decode(row);
        if(active!=null) profiles.put(mode(active.settings()),active);
        // Deployment settings remain the initial choice for modes not saved yet.
        profiles.putIfAbsent(mode(d.settings()),deploymentProfile(row,d));
        return profiles;
    }
    private boolean keyConfigured(RuntimeProfile p,Deployment d) {
        return mode(p.settings())==ConnectionMode.REMOTE && ((p.apiKey()!=null && !p.apiKey().isBlank())
            || (p.useEnvironmentKey() && sameEndpoint(p.settings(),d.settings()) && d.apiKeyConfigured()));
    }
    private String encryptConnections(Slot slot,Map<ConnectionMode,RuntimeProfile> profiles) {
        try { return cipher.encrypt(slot.name()+":connections",json.writeValueAsString(new RetainedConnections(profiles))); }
        catch(BusinessException e){throw e;} catch(Exception ignored){throw invalid("配置无法保存。");}
    }
    private View view(Row row, Deployment d) {
        RuntimeProfile p=decode(row);
        boolean configured=p==null?d.apiKeyConfigured():keyConfigured(p,d);
        var saved=connections(row,d).entrySet().stream().map(e->new SavedConnection(e.getKey(),e.getValue().settings(),keyConfigured(e.getValue(),d))).toList();
        return new View(row.slot(),row.version(),p==null?"DEPLOYMENT":"ADMIN",p==null?d.settings():p.settings(),configured,row.updated(),d.runtimeEnabled(),saved);
    }
    public List<View> list(AuthenticatedUser user) {
        admin(user); List<Deployment> deployment=client.deployment();
        return Arrays.stream(Slot.values()).map(slot->view(row(slot),deployment.stream().filter(d->d.slot()==slot).findFirst().orElseThrow())).toList();
    }
    public List<RuntimeProfile> snapshot() { return rows().stream().map(this::decode).filter(Objects::nonNull).toList(); }
    private void version(Row row,long expected) { if(row.version()!=expected) throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION,"配置已被修改，请刷新后重试。"); }
    private RuntimeProfile candidate(Slot slot, Update update, Row row, Deployment d) {
        version(row,update.expectedVersion());
        Settings s=update.settings();
        boolean inherit=s.provider().equals("inherit"), ollama=s.provider().equals("ollama");
        if(inherit && slot!=Slot.ANIMATION) throw invalid("通用模型不能选择跟随。");
        if(!inherit) {
            if(s.model().isBlank() || !s.model().equals(s.model().trim())) throw invalid("请填写模型名称。");
            validateBaseUrl(s.baseUrl(),ollama);
        }
        RuntimeProfile retained=connections(row,d).get(mode(s));
        if((retained==null || !sameEndpoint(s,retained.settings())) && sameEndpoint(s,d.settings())) retained=deploymentProfile(row,d);
        String key=update.apiKey()==null || update.apiKey().isBlank()?null:update.apiKey().trim();
        boolean environment=false;
        if(inherit || ollama) key=null;
        else if(key==null) {
            if(retained==null || !sameEndpoint(s,retained.settings()))
                throw invalid("更换服务商或 API 地址时，请填写新密钥。");
            key=retained.apiKey();environment=retained.useEnvironmentKey();
        }
        return new RuntimeProfile(slot,row.version()+1,s,key,environment);
    }
    public View save(AuthenticatedUser user, Slot slot, Update update) {
        admin(user); Row before=row(slot); Deployment d=deployment(slot);
        if(!d.runtimeEnabled()) throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE,"AI 服务尚未启用在线配置，请更新服务后重试。");
        RuntimeProfile candidate=candidate(slot,update,before,d);
        if(!client.check(candidate,false).success()) throw invalid("模型配置校验失败。");
        final String encrypted;
        try { encrypted=cipher.encrypt(slot.name(),json.writeValueAsString(candidate)); }
        catch(BusinessException e){throw e;} catch(Exception ignored){throw invalid("配置无法保存。");}
        Map<ConnectionMode,RuntimeProfile> retained=connections(before,d);
        retained.put(mode(candidate.settings()),candidate);
        String encryptedConnections=encryptConnections(slot,retained);
        tx.executeWithoutResult(status->{
            int updated=db.update("UPDATE edu_model_settings SET encrypted_config=?,encrypted_connections=?,version_no=version_no+1,updated_by=?,updated_at=? WHERE slot=? AND version_no=?",
                encrypted,encryptedConnections,user.id(),Timestamp.valueOf(LocalDateTime.now()),slot.name(),update.expectedVersion());
            if(updated!=1) throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION,"配置已被修改，请刷新后重试。");
            audit.recordRequired("MODEL_SETTINGS_UPDATE","MODEL_SETTINGS",slot.name(),user.id(),true,Map.of("version",candidate.version()));
        });
        return view(row(slot),d);
    }
    public TestResult test(AuthenticatedUser user,Slot slot,Update update) {
        admin(user); RuntimeProfile p=candidate(slot,update,row(slot),deployment(slot));
        return client.check(p,true);
    }
    public CatalogResult models(AuthenticatedUser user,Slot slot,CatalogRequest request) {
        admin(user); version(row(slot),request.expectedVersion());
        validateBaseUrl(request.baseUrl(),true);
        return client.models(request.baseUrl().replaceAll("/+$", ""));
    }
    private void validateBaseUrl(String url,boolean ollama) {
        try {
            URI uri=URI.create(url);
            String path=Optional.ofNullable(uri.getPath()).orElse("").replaceAll("/+$", "");
            if(uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                    || !Set.of("http","https").contains(uri.getScheme()) || uri.getPort()>65535
                    || path.endsWith("/chat/completions")
                    || (ollama && Set.of("/api/chat","/api/tags","/api/generate","/api","/v1").stream().anyMatch(path::endsWith)))
                throw invalid(ollama?"请填写 Ollama 服务地址，不要附加 /api/chat、/api/tags 或 /v1。":"请填写有效的 API 基础地址。");
        } catch(IllegalArgumentException e) { throw invalid("请填写有效的服务地址。"); }
    }
    public View reset(AuthenticatedUser user,Slot slot,long expected) {
        admin(user); Deployment d=deployment(slot); Row before=row(slot);version(before,expected);
        String encryptedConnections=encryptConnections(slot,connections(before,d));
        tx.executeWithoutResult(status->{
            int updated=db.update("UPDATE edu_model_settings SET encrypted_config=NULL,encrypted_connections=?,version_no=version_no+1,updated_by=?,updated_at=? WHERE slot=? AND version_no=?",
                encryptedConnections,user.id(),Timestamp.valueOf(LocalDateTime.now()),slot.name(),expected);
            if(updated!=1) throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION,"配置已被修改，请刷新后重试。");
            audit.recordRequired("MODEL_SETTINGS_RESET","MODEL_SETTINGS",slot.name(),user.id(),true,Map.of("version",expected+1));
        });
        return view(row(slot),d);
    }
    private BusinessException invalid(String text) { return new BusinessException(ErrorCode.VALIDATION_ERROR,text); }
}
