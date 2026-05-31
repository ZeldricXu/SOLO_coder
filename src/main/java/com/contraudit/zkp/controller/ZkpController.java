package com.contraudit.zkp.controller;

import com.contraudit.common.ApiResponse;
import com.contraudit.zkp.entity.ZkpCircuit;
import com.contraudit.zkp.entity.ZkpVerification;
import com.contraudit.zkp.service.ZkpService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/zkp")
@RequiredArgsConstructor
public class ZkpController {

    private final ZkpService zkpService;

    @PostMapping("/circuits")
    public Mono<ApiResponse<ZkpCircuit>> registerCircuit(@Valid @RequestBody ZkpCircuit circuit) {
        return Mono.just(ApiResponse.created(zkpService.registerCircuit(circuit)));
    }

    @GetMapping("/circuits/{id}")
    public Mono<ApiResponse<ZkpCircuit>> getCircuit(@PathVariable String id) {
        return Mono.just(ApiResponse.success(zkpService.getCircuit(id)));
    }

    @GetMapping("/circuits")
    public Mono<ApiResponse<List<ZkpCircuit>>> listCircuits(
            @RequestParam(required = false) String circuitType,
            @RequestParam(required = false) String circuitName) {
        return Mono.just(ApiResponse.success(zkpService.listCircuits(circuitType, circuitName)));
    }

    @PostMapping("/verify")
    public Mono<ApiResponse<ZkpVerification>> verifyProof(@RequestBody Map<String, Object> request) {
        String circuitId = (String) request.get("circuitId");
        String proofData = (String) request.get("proofData");
        String publicInputs = (String) request.get("publicInputs");
        String verifierAddress = (String) request.get("verifierAddress");

        if (proofData == null) {
            try {
                Object proofObj = request.get("proof");
                if (proofObj != null) {
                    proofData = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(proofObj);
                }
            } catch (Exception e) {
                proofData = "{}";
            }
        }

        return Mono.just(ApiResponse.success(
                zkpService.verifyProof(circuitId, proofData, publicInputs, verifierAddress)));
    }

    @GetMapping("/verifications/{verificationId}")
    public Mono<ApiResponse<ZkpVerification>> getVerification(@PathVariable String verificationId) {
        return Mono.just(ApiResponse.success(zkpService.getVerification(verificationId)));
    }

    @GetMapping("/verifications")
    public Mono<ApiResponse<List<ZkpVerification>>> listVerifications(
            @RequestParam(required = false) String circuitId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String verifierAddress) {
        return Mono.just(ApiResponse.success(
                zkpService.listVerifications(circuitId, status, verifierAddress)));
    }
}
