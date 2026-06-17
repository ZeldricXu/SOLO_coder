package com.designsystem.controller.api;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.designsystem.common.PageQuery;
import com.designsystem.common.Result;
import com.designsystem.common.enums.ExportFormat;
import com.designsystem.entity.Component;
import com.designsystem.entity.DesignToken;
import com.designsystem.entity.TokenOverride;
import com.designsystem.service.DesignTokenService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tokens")
public class TokenApiController {

    private final DesignTokenService tokenService;

    public TokenApiController(DesignTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @GetMapping
    public Result<IPage<DesignToken>> list(PageQuery query,
                                           @RequestParam(required = false) String tokenType,
                                           @RequestParam(required = false) String tokenLevel,
                                           @RequestParam(required = false) String category) {
        return Result.success(tokenService.getTokenPage(query, tokenType, tokenLevel, category));
    }

    @GetMapping("/tree")
    public Result<List<DesignToken>> getTree() {
        return Result.success(tokenService.getTokenTree());
    }

    @GetMapping("/{id}")
    public Result<DesignToken> getById(@PathVariable Long id) {
        return Result.success(tokenService.getTokenById(id));
    }

    @GetMapping("/name/{name}")
    public Result<DesignToken> getByName(@PathVariable String name) {
        return Result.success(tokenService.getTokenByName(name));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'DESIGNER')")
    public Result<DesignToken> create(@RequestBody DesignToken token) {
        return Result.success(tokenService.createToken(token));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'DESIGNER')")
    public Result<DesignToken> update(@PathVariable Long id, @RequestBody DesignToken token) {
        token.setId(id);
        return Result.success(tokenService.updateToken(token));
    }

    @GetMapping("/export")
    public ResponseEntity<String> export(@RequestParam ExportFormat format,
                                         @RequestParam(required = false) String tokenType,
                                         @RequestParam(required = false) String tokenLevel) {
        String content = tokenService.exportTokens(format, tokenType, tokenLevel);
        String filename = "design-tokens." + format.getCode();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(getContentType(format)))
                .body(content);
    }

    @GetMapping("/{id}/impact")
    public Result<Map<String, Object>> getImpactAnalysis(@PathVariable Long id) {
        return Result.success(tokenService.getTokenImpactAnalysis(id));
    }

    @GetMapping("/{id}/components")
    public Result<List<Component>> getAffectedComponents(@PathVariable Long id) {
        return Result.success(tokenService.getAffectedComponents(id));
    }

    @PostMapping("/{id}/overrides")
    @PreAuthorize("hasAnyRole('ADMIN', 'DESIGNER')")
    public Result<TokenOverride> addOverride(@PathVariable Long id, @RequestBody TokenOverride override) {
        override.setTokenId(id);
        return Result.success(tokenService.addOverride(override));
    }

    @GetMapping("/{id}/overrides")
    public Result<List<TokenOverride>> getOverrides(@PathVariable Long id) {
        return Result.success(tokenService.getOverridesByTokenId(id));
    }

    private String getContentType(ExportFormat format) {
        return switch (format) {
            case CSS -> "text/css";
            case JS -> "application/javascript";
            case JSON -> "application/json";
            case SCSS -> "text/x-scss";
            case LESS -> "text/less";
            case ANDROID -> "application/xml";
            case IOS -> "text/swift";
        };
    }
}
