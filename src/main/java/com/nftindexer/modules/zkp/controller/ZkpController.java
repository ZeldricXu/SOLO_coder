package com.nftindexer.modules.zkp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nftindexer.common.ApiResponse;
import com.nftindexer.common.PageResult;
import com.nftindexer.entity.ZkpCircuit;
import com.nftindexer.entity.ZkpProof;
import com.nftindexer.modules.zkp.dto.ZkpCircuitCreateRequest;
import com.nftindexer.modules.zkp.dto.ZkpVerifyRequest;
import com.nftindexer.modules.zkp.service.ZkpService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/zkp")
@RequiredArgsConstructor
public class ZkpController {

    private final ZkpService zkpService;

    @PostMapping("/circuits")
    public Mono<ApiResponse<ZkpCircuit>> registerCircuit(
            @Valid @RequestBody ZkpCircuitCreateRequest request) {
        return zkpService.registerCircuit(request)
                .map(circuit -> ApiResponse.created(circuit));
    }

    @GetMapping("/circuits/{circuitId}")
    public Mono<ApiResponse<ZkpCircuit>> getCircuit(@PathVariable String circuitId) {
        return zkpService.getCircuit(circuitId)
                .map(ApiResponse::success);
    }

    @GetMapping("/circuits/name/{circuitName}")
    public Mono<ApiResponse<ZkpCircuit>> getCircuitByName(@PathVariable String circuitName) {
        return zkpService.getCircuitByName(circuitName)
                .map(ApiResponse::success);
    }

    @GetMapping("/circuits")
    public Mono<ApiResponse<PageResult<ZkpCircuit>>> listCircuits(
            @RequestParam(required = false) String circuitType,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return zkpService.listCircuits(circuitType, status, pageNum, pageSize)
                .map(page -> ApiResponse.success(new PageResult<>(
                        page.getRecords(),
                        page.getTotal(),
                        page.getCurrent(),
                        page.getSize()
                )));
    }

    @PutMapping("/circuits/{circuitId}/deactivate")
    public Mono<ApiResponse<ZkpCircuit>> deactivateCircuit(
            @PathVariable String circuitId,
            @RequestBody(required = false) Map<String, String> request) {
        String deactivatedBy = request != null ?
                request.getOrDefault("deactivatedBy", "system") : "system";
        return zkpService.deactivateCircuit(circuitId, deactivatedBy)
                .map(ApiResponse::success);
    }

    @PostMapping("/verify")
    public Mono<ApiResponse<ZkpProof>> verifyProof(
            @Valid @RequestBody ZkpVerifyRequest request) {
        return zkpService.verifyProof(request)
                .map(proof -> ApiResponse.created(proof));
    }

    @GetMapping("/proofs/{proofId}")
    public Mono<ApiResponse<ZkpProof>> getProof(@PathVariable String proofId) {
        return zkpService.getProof(proofId)
                .map(ApiResponse::success);
    }

    @GetMapping("/proofs")
    public Mono<ApiResponse<PageResult<ZkpProof>>> listProofs(
            @RequestParam(required = false) String circuitId,
            @RequestParam(required = false) String circuitName,
            @RequestParam(required = false) Boolean verified,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return zkpService.listProofs(circuitId, circuitName, verified, status, pageNum, pageSize)
                .map(page -> ApiResponse.success(new PageResult<>(
                        page.getRecords(),
                        page.getTotal(),
                        page.getCurrent(),
                        page.getSize()
                )));
    }

    @GetMapping("/stats")
    public Mono<ApiResponse<Map<String, Object>>> getProofStats(
            @RequestParam(required = false) String circuitId) {
        return zkpService.getProofStats(circuitId)
                .map(ApiResponse::success);
    }
}
