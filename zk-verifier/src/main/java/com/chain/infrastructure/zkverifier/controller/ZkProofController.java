package com.chain.infrastructure.zkverifier.controller;

import com.chain.infrastructure.common.dto.ApiResponse;
import com.chain.infrastructure.persistence.entity.ZkProof;
import com.chain.infrastructure.zkverifier.dto.ZkProofRequest;
import com.chain.infrastructure.zkverifier.dto.ZkProofResult;
import com.chain.infrastructure.zkverifier.service.ZkProofService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.List;

@RestController
@RequestMapping("/api/v1/zkp")
@RequiredArgsConstructor
public class ZkProofController {

    private final ZkProofService zkProofService;

    @PostMapping("/verify")
    public Mono<ApiResponse<ZkProofResult>> verifyProof(@RequestBody ZkProofRequest request) {
        return zkProofService.verifyProof(request)
                .map(ApiResponse::success);
    }

    @PostMapping("/verify/batch")
    public Mono<ApiResponse<ZkProofResult>> verifyProofBatch(@RequestBody List<ZkProofRequest> requests) {
        return zkProofService.verifyProofBatch(requests)
                .map(ApiResponse::success);
    }

    @GetMapping("/proofs/{proofId}")
    public Mono<ApiResponse<ZkProof>> getProof(@PathVariable String proofId) {
        return zkProofService.getProof(proofId)
                .map(ApiResponse::success);
    }
}
