package com.didauth.module.zkp.controller;

import com.didauth.common.response.ApiResponse;
import com.didauth.core.entity.ZkpProof;
import com.didauth.module.zkp.dto.ZkpVerifyRequest;
import com.didauth.module.zkp.dto.ZkpVerifyResponse;
import com.didauth.module.zkp.service.ZkpService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/zkp")
@RequiredArgsConstructor
public class ZkpController {

    private final ZkpService zkpService;

    @PostMapping("/verify")
    public Mono<ApiResponse<ZkpVerifyResponse>> verifyProof(@Valid @RequestBody ZkpVerifyRequest request) {
        return zkpService.verifyProof(request)
                .map(ApiResponse::success);
    }

    @GetMapping("/proofs/{proofId}")
    public Mono<ApiResponse<ZkpProof>> getProofStatus(@PathVariable String proofId) {
        return zkpService.getProofStatus(proofId)
                .map(ApiResponse::success);
    }
}
