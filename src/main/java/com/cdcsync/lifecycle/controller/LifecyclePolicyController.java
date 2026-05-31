package com.cdcsync.lifecycle.controller;

import com.cdcsync.common.api.PageResult;
import com.cdcsync.common.api.Result;
import com.cdcsync.lifecycle.domain.LifecyclePolicy;
import com.cdcsync.lifecycle.service.LifecyclePolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/lifecycle/policies")
@RequiredArgsConstructor
public class LifecyclePolicyController {

    private final LifecyclePolicyService lifecyclePolicyService;

    @PostMapping
    public Result<LifecyclePolicy> create(@RequestBody LifecyclePolicy policy) {
        return Result.success(lifecyclePolicyService.create(policy));
    }

    @PutMapping
    public Result<LifecyclePolicy> update(@RequestBody LifecyclePolicy policy) {
        return Result.success(lifecyclePolicyService.update(policy));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        lifecyclePolicyService.delete(id);
        return Result.success();
    }

    @GetMapping("/{id}")
    public Result<LifecyclePolicy> findById(@PathVariable String id) {
        return Result.success(lifecyclePolicyService.findById(id));
    }

    @GetMapping
    public Result<PageResult<LifecyclePolicy>> findPage(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return Result.success(lifecyclePolicyService.findPage(pageNum, pageSize));
    }

    @PostMapping("/{policyId}/apply/{resourceId}")
    public Result<Void> applyPolicy(@PathVariable String policyId, @PathVariable String resourceId) {
        lifecyclePolicyService.applyPolicy(policyId, resourceId);
        return Result.success();
    }

    @PostMapping("/migrate/warm/{resourceId}")
    public Result<Void> migrateToWarmStorage(@PathVariable String resourceId) {
        lifecyclePolicyService.migrateToWarmStorage(resourceId);
        return Result.success();
    }

    @PostMapping("/migrate/cold/{resourceId}")
    public Result<Void> migrateToColdStorage(@PathVariable String resourceId) {
        lifecyclePolicyService.migrateToColdStorage(resourceId);
        return Result.success();
    }

    @PostMapping("/archive/{resourceId}")
    public Result<Void> archiveData(@PathVariable String resourceId) {
        lifecyclePolicyService.archiveData(resourceId);
        return Result.success();
    }

    @PostMapping("/purge/{resourceId}")
    public Result<Void> purgeExpiredData(@PathVariable String resourceId) {
        lifecyclePolicyService.purgeExpiredData(resourceId);
        return Result.success();
    }
}
