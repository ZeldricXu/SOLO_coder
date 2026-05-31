package com.didauth.module.zkp.enhanced;

import com.didauth.common.response.ApiResponse;
import com.didauth.module.zkp.dto.ZkpVerifyRequest;
import com.didauth.module.zkp.dto.ZkpVerifyResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v2/zkp")
@RequiredArgsConstructor
public class EnhancedZkpController {

    private final EnhancedZkpService enhancedZkpService;

    @PostMapping("/verify")
    public Mono<ApiResponse<ZkpVerifyResponse>> verifyProof(@Valid @RequestBody ZkpVerifyRequest request) {
        return enhancedZkpService.verifyProof(request)
                .map(ApiResponse::success);
    }

    @GetMapping("/proofs/{proofId}")
    public Mono<ApiResponse<com.didauth.core.entity.ZkpProof>> getProofStatus(@PathVariable String proofId) {
        return enhancedZkpService.getProofStatus(proofId)
                .map(ApiResponse::success);
    }

    @PostMapping("/cache/warmup")
    public Mono<ApiResponse<Boolean>> warmUpCache(@RequestBody Map<String, ZkpVerifyRequest> proofs) {
        return enhancedZkpService.warmUpCache(proofs)
                .map(ApiResponse::success);
    }

    @PostMapping("/cache/invalidate")
    public Mono<ApiResponse<Void>> invalidateProof(@RequestBody Map<String, String> request) {
        String circuitId = request.get("circuitId");
        String proofData = request.get("proofData");
        return enhancedZkpService.invalidateProof(circuitId, proofData)
                .then(Mono.just(ApiResponse.success(null)));
    }

    @PostMapping("/cache/invalidate/all")
    public Mono<ApiResponse<Void>> invalidateAll() {
        return enhancedZkpService.invalidateAll()
                .then(Mono.just(ApiResponse.success(null)));
    }

    @GetMapping("/cache/metrics")
    public Mono<ApiResponse<Map<String, Object>>> getCacheMetrics() {
        return enhancedZkpService.getCacheMetrics()
                .map(ApiResponse::success);
    }
}
