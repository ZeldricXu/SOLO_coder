package com.designsystem.controller.api;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.designsystem.common.PageQuery;
import com.designsystem.common.Result;
import com.designsystem.entity.Component;
import com.designsystem.entity.ComponentVersion;
import com.designsystem.service.ComponentService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/components")
public class ComponentApiController {

    private final ComponentService componentService;

    public ComponentApiController(ComponentService componentService) {
        this.componentService = componentService;
    }

    @GetMapping
    public Result<IPage<Component>> list(PageQuery query,
                                         @RequestParam(required = false) String category,
                                         @RequestParam(required = false) String framework) {
        return Result.success(componentService.getComponentPage(query, category, framework));
    }

    @GetMapping("/{id}")
    public Result<Component> getById(@PathVariable Long id) {
        return Result.success(componentService.getComponentById(id));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'DEVELOPER')")
    public Result<Component> create(@RequestBody Component component) {
        return Result.success(componentService.createComponent(component));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'DEVELOPER')")
    public Result<Component> update(@PathVariable Long id, @RequestBody Component component) {
        component.setId(id);
        return Result.success(componentService.updateComponent(component));
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAnyRole('ADMIN', 'DEVELOPER')")
    public Result<Void> publish(@PathVariable Long id, @RequestParam String version) {
        componentService.publishComponent(id, version);
        return Result.success();
    }

    @PostMapping("/{id}/rollback")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> rollback(@PathVariable Long id, @RequestParam String version) {
        componentService.rollbackVersion(id, version);
        return Result.success();
    }

    @GetMapping("/{id}/versions")
    public Result<List<ComponentVersion>> getVersions(@PathVariable Long id) {
        return Result.success(componentService.getVersionsByComponentId(id));
    }

    @PostMapping("/{id}/versions")
    @PreAuthorize("hasAnyRole('ADMIN', 'DEVELOPER')")
    public Result<ComponentVersion> createVersion(@PathVariable Long id, @RequestBody ComponentVersion version) {
        version.setComponentId(id);
        return Result.success(componentService.createVersion(version));
    }

    @GetMapping("/versions/{versionId}")
    public Result<ComponentVersion> getVersion(@PathVariable Long versionId) {
        return Result.success(componentService.getVersionById(versionId));
    }

    @GetMapping("/token/{tokenId}")
    public Result<List<Component>> getByTokenId(@PathVariable Long tokenId) {
        return Result.success(componentService.getComponentsByTokenId(tokenId));
    }
}
