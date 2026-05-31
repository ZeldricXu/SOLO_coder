package com.datastandard.modules.core.controller;

import com.datastandard.common.dto.ApiResponse;
import com.datastandard.modules.core.CoreService;
import com.datastandard.modules.core.dto.TransformRequest;
import com.datastandard.modules.core.dto.TransformResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
@RequestMapping("/api/v1/core")
@RequiredArgsConstructor
@Validated
@Tag(name = "核心处理", description = "数据标准化核心处理API")
public class CoreProcessingController {

    private final CoreService coreService;

    @PostMapping("/transform")
    @Operation(summary = "数据转换", description = "执行数据标准化转换处理")
    @PreAuthorize("hasAuthority('core:transform')")
    public Mono<ResponseEntity<ApiResponse<TransformResponse>>> transform(
            @Valid @RequestBody TransformRequest request) {
        log.info("执行数据转换: requestId={}, dataSource={}", request.getRequestId(), request.getDataSource());
        return coreService.executeHandler(request)
                .map(response -> ResponseEntity.ok(ApiResponse.success("数据转换完成", response)))
                .onErrorResume(e -> {
                    log.error("数据转换失败", e);
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(ApiResponse.error("TRANSFORM_FAILED", "数据转换失败: " + e.getMessage())));
                });
    }

    @PostMapping("/validate")
    @Operation(summary = "数据校验", description = "校验数据格式和规则")
    @PreAuthorize("hasAuthority('core:validate')")
    public Mono<ResponseEntity<ApiResponse<TransformResponse>>> validate(
            @Valid @RequestBody TransformRequest request) {
        log.info("执行数据校验: requestId={}", request.getRequestId());
        return coreService.executeHandler(request)
                .map(response -> ResponseEntity.ok(ApiResponse.success("数据校验完成", response)))
                .onErrorResume(e -> Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(ApiResponse.error("VALIDATE_FAILED", "数据校验失败: " + e.getMessage()))));
    }

    @GetMapping("/health")
    @Operation(summary = "健康检查", description = "检查核心处理服务健康状态")
    @PreAuthorize("hasAuthority('core:health')")
    public Mono<ResponseEntity<ApiResponse<String>>> healthCheck() {
        return Mono.just(ResponseEntity.ok(ApiResponse.success("核心处理服务运行正常")));
    }
}
