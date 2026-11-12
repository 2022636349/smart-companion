package com.education.agent.modelsettings;

import static com.education.agent.modelsettings.ModelSettingsDtos.*;
import com.education.agent.common.api.ApiResponse;
import com.education.agent.common.web.RequestIds;
import com.education.agent.common.error.*;
import com.education.agent.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
public class ModelSettingsController {
    private final ModelSettingsService service; private final byte[] token;
    public ModelSettingsController(ModelSettingsService service,@Value("${education.ai-service.internal-token:}") String token) {
        this.service=service;this.token=token.getBytes(StandardCharsets.UTF_8);
    }
    @ModelAttribute public void noCache(HttpServletResponse response) { response.setHeader("Cache-Control","no-store"); }
    @GetMapping("/api/admin/model-settings")
    public ApiResponse<List<View>> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(RequestIds.current(),service.list(user));
    }
    @PutMapping("/api/admin/model-settings/{slot}")
    public ApiResponse<View> save(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable Slot slot,@Valid @RequestBody Update update) {
        return ApiResponse.ok(RequestIds.current(),service.save(user,slot,update));
    }
    @PostMapping("/api/admin/model-settings/{slot}/test")
    public ApiResponse<TestResult> test(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable Slot slot,@Valid @RequestBody Update update) {
        return ApiResponse.ok(RequestIds.current(),service.test(user,slot,update));
    }
    @DeleteMapping("/api/admin/model-settings/{slot}")
    public ApiResponse<View> reset(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable Slot slot,@RequestParam @Min(0) long expectedVersion) {
        return ApiResponse.ok(RequestIds.current(),service.reset(user,slot,expectedVersion));
    }
    @PostMapping("/api/admin/model-settings/{slot}/models")
    public ApiResponse<CatalogResult> models(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable Slot slot,@Valid @RequestBody CatalogRequest request) {
        return ApiResponse.ok(RequestIds.current(),service.models(user,slot,request));
    }
    @GetMapping("/api/internal/ai/model-settings")
    public List<RuntimeProfile> snapshot(@RequestHeader(value="X-Internal-Token",required=false) String supplied,
            @RequestHeader(value="X-Request-Id",required=false) String id) {
        if(token.length==0 || supplied==null || !MessageDigest.isEqual(token,supplied.getBytes(StandardCharsets.UTF_8)))
            throw new BusinessException(ErrorCode.UNAUTHENTICATED,"内部令牌无效。");
        if(id==null || !id.matches("[A-Za-z0-9._:-]{16,64}")) throw new BusinessException(ErrorCode.VALIDATION_ERROR,"请求编号无效。");
        return service.snapshot();
    }
}
