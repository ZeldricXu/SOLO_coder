package com.stockmgmt.controller;

import com.stockmgmt.common.Result;
import com.stockmgmt.entity.LockTimeoutConfig;
import com.stockmgmt.entity.WarningAggregationConfig;
import com.stockmgmt.entity.WarningThresholdConfig;
import com.stockmgmt.service.LockTimeoutConfigService;
import com.stockmgmt.service.WarningAggregationConfigService;
import com.stockmgmt.service.WarningThresholdConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/config")
public class ConfigController {

    @Autowired
    private LockTimeoutConfigService lockTimeoutConfigService;

    @Autowired
    private WarningAggregationConfigService warningAggregationConfigService;

    @Autowired
    private WarningThresholdConfigService warningThresholdConfigService;

    @PostMapping("/lock-timeout")
    public Result<LockTimeoutConfig> createLockTimeoutConfig(@RequestBody LockTimeoutConfig config) {
        LockTimeoutConfig saved = lockTimeoutConfigService.saveConfig(config);
        return Result.success(saved);
    }

    @PutMapping("/lock-timeout/{id}")
    public Result<LockTimeoutConfig> updateLockTimeoutConfig(
            @PathVariable Long id,
            @RequestBody LockTimeoutConfig config) {
        config.setId(id);
        LockTimeoutConfig saved = lockTimeoutConfigService.saveConfig(config);
        return Result.success(saved);
    }

    @DeleteMapping("/lock-timeout/{id}")
    public Result<Void> deleteLockTimeoutConfig(@PathVariable Long id) {
        lockTimeoutConfigService.deleteConfig(id);
        return Result.success();
    }

    @GetMapping("/lock-timeout/{id}")
    public Result<LockTimeoutConfig> getLockTimeoutConfig(@PathVariable Long id) {
        return lockTimeoutConfigService.getConfigById(id)
                .map(Result::success)
                .orElse(Result.error("配置不存在"));
    }

    @GetMapping("/lock-timeout/refresh")
    public Result<Void> refreshLockTimeoutConfigs() {
        lockTimeoutConfigService.refreshCache();
        return Result.success();
    }

    @PostMapping("/warning-aggregation")
    public Result<WarningAggregationConfig> createWarningAggregationConfig(
            @RequestBody WarningAggregationConfig config) {
        WarningAggregationConfig saved = warningAggregationConfigService.saveConfig(config);
        return Result.success(saved);
    }

    @PutMapping("/warning-aggregation/{id}")
    public Result<WarningAggregationConfig> updateWarningAggregationConfig(
            @PathVariable Long id,
            @RequestBody WarningAggregationConfig config) {
        config.setId(id);
        WarningAggregationConfig saved = warningAggregationConfigService.saveConfig(config);
        return Result.success(saved);
    }

    @DeleteMapping("/warning-aggregation/{id}")
    public Result<Void> deleteWarningAggregationConfig(@PathVariable Long id) {
        warningAggregationConfigService.deleteConfig(id);
        return Result.success();
    }

    @GetMapping("/warning-aggregation/{id}")
    public Result<WarningAggregationConfig> getWarningAggregationConfig(@PathVariable Long id) {
        return warningAggregationConfigService.getConfigById(id)
                .map(Result::success)
                .orElse(Result.error("配置不存在"));
    }

    @GetMapping("/warning-aggregation/refresh")
    public Result<Void> refreshWarningAggregationConfigs() {
        warningAggregationConfigService.refreshCache();
        return Result.success();
    }

    @PostMapping("/warning-threshold")
    public Result<WarningThresholdConfig> createWarningThresholdConfig(
            @RequestBody WarningThresholdConfig config) {
        WarningThresholdConfig saved = warningThresholdConfigService.saveConfig(config);
        return Result.success(saved);
    }

    @PutMapping("/warning-threshold/{id}")
    public Result<WarningThresholdConfig> updateWarningThresholdConfig(
            @PathVariable Long id,
            @RequestBody WarningThresholdConfig config) {
        config.setId(id);
        WarningThresholdConfig saved = warningThresholdConfigService.saveConfig(config);
        return Result.success(saved);
    }

    @DeleteMapping("/warning-threshold/{id}")
    public Result<Void> deleteWarningThresholdConfig(@PathVariable Long id) {
        warningThresholdConfigService.deleteConfig(id);
        return Result.success();
    }

    @GetMapping("/warning-threshold/{id}")
    public Result<WarningThresholdConfig> getWarningThresholdConfig(@PathVariable Long id) {
        return warningThresholdConfigService.getConfigById(id)
                .map(Result::success)
                .orElse(Result.error("配置不存在"));
    }

    @GetMapping("/warning-threshold/list")
    public Result<List<WarningThresholdConfig>> getAllWarningThresholdConfigs() {
        return Result.success(warningThresholdConfigService.getAllConfigs());
    }

    @GetMapping("/warning-threshold/refresh")
    public Result<Void> refreshWarningThresholdConfigs() {
        warningThresholdConfigService.refreshCache();
        return Result.success();
    }
}
