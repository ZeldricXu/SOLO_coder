package com.iotplatform.config.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.iotplatform.common.dto.PageQuery;
import com.iotplatform.common.dto.PageResult;
import com.iotplatform.common.dto.Result;
import com.iotplatform.config.dto.ConfigCreateDTO;
import com.iotplatform.config.dto.ConfigRollbackDTO;
import com.iotplatform.config.dto.ConfigUpdateDTO;
import com.iotplatform.config.entity.SysConfig;
import com.iotplatform.config.entity.SysConfigHistory;
import com.iotplatform.config.service.ConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.List;

@RestController
@RequestMapping("/api/v1/config")
@RequiredArgsConstructor
public class ConfigController {

    private final ConfigService configService;

    @PostMapping
    public Mono<Result<SysConfig>> createConfig(@Valid @RequestBody ConfigCreateDTO dto) {
        return configService.createConfig(dto)
                .map(Result::success);
    }

    @PutMapping
    public Mono<Result<SysConfig>> updateConfig(@Valid @RequestBody ConfigUpdateDTO dto) {
        return configService.updateConfig(dto)
                .map(Result::success);
    }

    @GetMapping("/{configId}")
    public Mono<Result<SysConfig>> getConfig(@PathVariable String configId,
                                             @RequestParam(defaultValue = "default") String namespace) {
        return configService.getConfig(configId, namespace)
                .map(Result::success);
    }

    @GetMapping("/{configId}/versions/{version}")
    public Mono<Result<SysConfig>> getConfigByVersion(@PathVariable String configId,
                                                      @PathVariable Integer version,
                                                      @RequestParam(defaultValue = "default") String namespace) {
        return configService.getConfigByVersion(configId, namespace, version)
                .map(Result::success);
    }

    @GetMapping("/key/{configKey}")
    public Mono<Result<SysConfig>> getConfigByKey(@PathVariable String configKey,
                                                  @RequestParam(defaultValue = "default") String namespace) {
        return configService.getConfigByKey(namespace, configKey)
                .map(opt -> opt.map(Result::success)
                        .orElse(Result.error(404, "配置不存在")));
    }

    @GetMapping
    public Mono<Result<PageResult<SysConfig>>> listConfigs(
            @RequestParam(required = false) String namespace,
            @RequestParam(required = false) String configKey,
            @RequestParam(required = false) Boolean enabled,
            @ModelAttribute PageQuery pageQuery) {
        return configService.listConfigs(namespace, configKey, enabled,
                        pageQuery.getPageNum(), pageQuery.getPageSize())
                .map(page -> {
                    PageResult<SysConfig> pageResult = new PageResult<>(
                            page.getRecords(),
                            page.getTotal(),
                            page.getPages(),
                            page.getCurrent(),
                            page.getSize()
                    );
                    return Result.success(pageResult);
                });
    }

    @GetMapping("/{configId}/history")
    public Mono<Result<List<SysConfigHistory>>> getConfigHistory(@PathVariable String configId,
                                                                  @RequestParam(defaultValue = "default") String namespace) {
        return configService.getConfigHistory(configId, namespace)
                .map(Result::success);
    }

    @PostMapping("/rollback")
    public Mono<Result<SysConfig>> rollbackConfig(@Valid @RequestBody ConfigRollbackDTO dto) {
        return configService.rollbackConfig(dto)
                .map(Result::success);
    }

    @DeleteMapping("/{configId}")
    public Mono<Result<Void>> deleteConfig(@PathVariable String configId,
                                           @RequestParam(defaultValue = "default") String namespace) {
        return configService.deleteConfig(configId, namespace)
                .then(Mono.just(Result.success(null)));
    }

    @PostMapping("/validate")
    public Mono<Result<Boolean>> validateConfig(@RequestParam String configKey,
                                                @RequestParam String configValue) {
        return configService.validateConfig(configKey, configValue)
                .map(Result::success);
    }
}
