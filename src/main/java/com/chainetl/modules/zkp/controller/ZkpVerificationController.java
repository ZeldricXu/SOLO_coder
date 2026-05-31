package com.chainetl.modules.zkp.controller;

import com.chainetl.common.dto.ApiResponse;
import com.chainetl.modules.zkp.dto.CircuitConfig;
import com.chainetl.modules.zkp.dto.ProofResponse;
import com.chainetl.modules.zkp.dto.VerifyProofRequest;
import com.chainetl.modules.zkp.service.ZkpVerificationService;
import io.micrometer.core.annotation.Timed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/v1/zkp")
@RequiredArgsConstructor
public class ZkpVerificationController {

    private final ZkpVerificationService zkpVerificationService;

    @PostMapping("/verify")
    @Timed(value = "zkp.controller.verify", description = "Time taken to handle ZKP verify request")
    public Mono<ResponseEntity<ApiResponse<ProofResponse>>> verifyProof(
            @Valid @RequestBody VerifyProofRequest request) {
        return zkpVerificationService.verifyProof(request)
                .map(proof -> ResponseEntity.status(HttpStatus.CREATED)
                        .body(ApiResponse.success(201, proof)));
    }

    @GetMapping("/proofs/{proofId}")
    @Timed(value = "zkp.controller.get.proof", description = "Time taken to get ZKP proof by ID")
    public Mono<ResponseEntity<ApiResponse<ProofResponse>>> getProof(
            @PathVariable String proofId) {
        return zkpVerificationService.getProof(proofId)
                .map(proof -> ResponseEntity.ok(ApiResponse.success(proof)));
    }

    @GetMapping("/proofs")
    @Timed(value = "zkp.controller.list.proofs", description = "Time taken to list ZKP proofs")
    public Mono<ResponseEntity<ApiResponse<List<ProofResponse>>>> listProofs(
            @RequestParam(required = false) String circuitId,
            @RequestParam(required = false) Boolean verificationResult) {
        return zkpVerificationService.listProofs(circuitId, verificationResult)
                .map(proofs -> ResponseEntity.ok(ApiResponse.success(proofs)));
    }

    @PostMapping("/proofs/{proofId}/retry")
    @Timed(value = "zkp.controller.retry.verification", description = "Time taken to retry ZKP verification")
    public Mono<ResponseEntity<ApiResponse<ProofResponse>>> retryVerification(
            @PathVariable String proofId) {
        return zkpVerificationService.retryVerification(proofId)
                .map(proof -> ResponseEntity.ok(ApiResponse.success(proof)));
    }

    @GetMapping("/circuits")
    @Timed(value = "zkp.controller.list.circuits", description = "Time taken to list supported ZKP circuits")
    public Mono<ResponseEntity<ApiResponse<List<CircuitConfig>>>> listCircuits() {
        return zkpVerificationService.listCircuits()
                .map(circuits -> ResponseEntity.ok(ApiResponse.success(circuits)));
    }
}
