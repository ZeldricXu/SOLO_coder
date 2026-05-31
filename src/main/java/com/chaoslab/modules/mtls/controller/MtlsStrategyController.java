package com.chaoslab.modules.mtls.controller;

import com.chaoslab.common.ApiResponse;
import com.chaoslab.entity.MtlsRevocationList;
import com.chaoslab.modules.mtls.dto.CertificateIssueRequest;
import com.chaoslab.modules.mtls.dto.CertificateResponse;
import com.chaoslab.modules.mtls.dto.RevocationRequest;
import com.chaoslab.modules.mtls.dto.StrategySwitchRequest;
import com.chaoslab.modules.mtls.service.MtlsCertificateStrategyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/mtls/strategy")
@RequiredArgsConstructor
public class MtlsStrategyController {

    private final MtlsCertificateStrategyService strategyService;

    @GetMapping
    public Mono<ApiResponse<Map<String, Object>>> listStrategies() {
        return strategyService.listStrategies()
                .map(ApiResponse::success);
    }

    @PostMapping("/activate/{strategyName}")
    public Mono<ApiResponse<String>> activateStrategy(
            @PathVariable String strategyName,
            @RequestBody StrategySwitchRequest request) {
        return strategyService.activateStrategy(strategyName, request.getOperator(), request.getReason())
                .map(ApiResponse::success);
    }

    @PostMapping("/deactivate/{strategyName}")
    public Mono<ApiResponse<String>> deactivateStrategy(
            @PathVariable String strategyName,
            @RequestBody StrategySwitchRequest request) {
        return strategyService.deactivateStrategy(strategyName, request.getOperator(), request.getReason())
                .map(ApiResponse::success);
    }

    @PostMapping("/switch")
    public Mono<ApiResponse<String>> switchStrategy(@RequestBody StrategySwitchRequest request) {
        String fromStrategy = request.getOperation() != null ? request.getOperation() : null;
        return strategyService.switchStrategy(
                        fromStrategy,
                        request.getStrategyName(),
                        request.getOperator(),
                        request.getReason())
                .map(ApiResponse::success);
    }

    @PostMapping("/certificate/issue/{strategyName}")
    public Mono<ApiResponse<CertificateResponse>> issueCertificateWithStrategy(
            @PathVariable String strategyName,
            @RequestBody CertificateIssueRequest request) {
        return strategyService.issueCertificateWithStrategy(request, strategyName)
                .map(ApiResponse::success);
    }

    @PostMapping("/certificate/revoke/{strategyName}")
    public Mono<ApiResponse<MtlsRevocationList>> revokeCertificateWithStrategy(
            @PathVariable String strategyName,
            @RequestBody RevocationRequest request) {
        return strategyService.revokeCertificateWithStrategy(request, strategyName)
                .map(ApiResponse::success);
    }

    @PostMapping("/certificate/rotate/{strategyName}")
    public Flux<ApiResponse<CertificateResponse>> rotateExpiringCertificatesWithStrategy(
            @PathVariable String strategyName) {
        return strategyService.rotateExpiringCertificatesWithStrategy(strategyName)
                .map(ApiResponse::success);
    }
}
