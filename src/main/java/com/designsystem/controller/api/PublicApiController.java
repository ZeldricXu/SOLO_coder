package com.designsystem.controller.api;

import com.designsystem.common.Result;
import com.designsystem.common.enums.ExportFormat;
import com.designsystem.entity.Component;
import com.designsystem.service.ChangeTrackingService;
import com.designsystem.service.ComponentService;
import com.designsystem.service.DesignTokenService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/public")
public class PublicApiController {

    private final ComponentService componentService;
    private final DesignTokenService tokenService;
    private final ChangeTrackingService changeService;

    public PublicApiController(ComponentService componentService, DesignTokenService tokenService,
                               ChangeTrackingService changeService) {
        this.componentService = componentService;
        this.tokenService = tokenService;
        this.changeService = changeService;
    }

    @GetMapping("/components")
    public Result<List<Component>> getComponents(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String framework) {
        com.designsystem.common.PageQuery query = new com.designsystem.common.PageQuery();
        query.setPageSize(1000L);
        return Result.success(componentService.getMarketplacePage(query, category, framework, null).getRecords());
    }

    @GetMapping("/components/{name}/{framework}")
    public Result<Component> getComponentByName(@PathVariable String name, @PathVariable String framework) {
        return Result.success(componentService.getComponentByNameAndFramework(name, framework));
    }

    @GetMapping("/tokens/css")
    public String getTokensCss() {
        return tokenService.exportTokens(ExportFormat.CSS, null, null);
    }

    @GetMapping("/tokens/js")
    public String getTokensJs() {
        return tokenService.exportTokens(ExportFormat.JS, null, null);
    }

    @GetMapping("/tokens/json")
    public String getTokensJson() {
        return tokenService.exportTokens(ExportFormat.JSON, null, null);
    }

    @GetMapping("/tokens/export/{format}")
    public String exportTokens(@PathVariable ExportFormat format,
                               @RequestParam(required = false) String tokenType,
                               @RequestParam(required = false) String tokenLevel) {
        return tokenService.exportTokens(format, tokenType, tokenLevel);
    }

    @GetMapping("/tokens/{name}")
    public Result<?> getTokenByName(@PathVariable String name) {
        return Result.success(tokenService.getTokenByName(name));
    }

    @GetMapping("/changelog/{componentId}")
    public Result<?> getChangelog(@PathVariable Long componentId) {
        return Result.success(changeService.getChangelogsByComponentId(componentId));
    }

    @GetMapping("/migrations")
    public Result<?> getPendingMigrations() {
        return Result.success(changeService.getPendingMigrations());
    }
}
