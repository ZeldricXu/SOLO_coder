package com.apishield.tee.controller;

import com.apishield.common.dto.Result;
import com.apishield.tee.domain.AttestationReport;
import com.apishield.tee.domain.TeeEnclave;
import com.apishield.tee.dto.AttestationRequest;
import com.apishield.tee.dto.EnclaveCreateRequest;
import com.apishield.tee.dto.EncryptRequest;
import com.apishield.tee.service.TeeEnclaveService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/tee")
@RequiredArgsConstructor
public class TeeEnclaveController {

    private final TeeEnclaveService teeEnclaveService;

    @PostMapping("/enclaves")
    public Mono<Result<TeeEnclave>> createEnclave(@RequestBody EnclaveCreateRequest request) {
        return Mono.just(Result.success(teeEnclaveService.createEnclave(request)));
    }

    @GetMapping("/enclaves/{enclaveId}")
    public Mono<Result<TeeEnclave>> getEnclave(@PathVariable String enclaveId) {
        return Mono.just(Result.success(teeEnclaveService.getEnclave(enclaveId)));
    }

    @GetMapping("/enclaves")
    public Mono<Result<List<TeeEnclave>>> getAllEnclaves() {
        return Mono.just(Result.success(teeEnclaveService.getAllEnclaves()));
    }

    @GetMapping("/enclaves/status/{status}")
    public Mono<Result<List<TeeEnclave>>> getEnclavesByStatus(@PathVariable TeeEnclave.EnclaveStatus status) {
        return Mono.just(Result.success(teeEnclaveService.getEnclavesByStatus(status)));
    }

    @PostMapping("/enclaves/{enclaveId}/start")
    public Mono<Result<TeeEnclave>> startEnclave(@PathVariable String enclaveId) {
        return Mono.just(Result.success(teeEnclaveService.startEnclave(enclaveId)));
    }

    @PostMapping("/enclaves/{enclaveId}/stop")
    public Mono<Result<TeeEnclave>> stopEnclave(@PathVariable String enclaveId) {
        return Mono.just(Result.success(teeEnclaveService.stopEnclave(enclaveId)));
    }

    @PostMapping("/enclaves/{enclaveId}/restart")
    public Mono<Result<TeeEnclave>> restartEnclave(@PathVariable String enclaveId) {
        return Mono.just(Result.success(teeEnclaveService.restartEnclave(enclaveId)));
    }

    @DeleteMapping("/enclaves/{enclaveId}")
    public Mono<Result<Void>> terminateEnclave(@PathVariable String enclaveId) {
        teeEnclaveService.terminateEnclave(enclaveId);
        return Mono.just(Result.success(null));
    }

    @PostMapping("/attestations")
    public Mono<Result<AttestationReport>> performAttestation(@RequestBody AttestationRequest request) {
        return Mono.just(Result.success(teeEnclaveService.performAttestation(request)));
    }

    @GetMapping("/attestations/{reportId}")
    public Mono<Result<AttestationReport>> getAttestationReport(@PathVariable String reportId) {
        return Mono.just(Result.success(teeEnclaveService.getAttestationReport(reportId)));
    }

    @GetMapping("/enclaves/{enclaveId}/attestations")
    public Mono<Result<List<AttestationReport>>> getAttestationReports(@PathVariable String enclaveId) {
        return Mono.just(Result.success(teeEnclaveService.getAttestationReports(enclaveId)));
    }

    @PostMapping("/attestations/{reportId}/verify")
    public Mono<Result<Boolean>> verifyAttestation(@PathVariable String reportId) {
        return Mono.just(Result.success(teeEnclaveService.verifyAttestation(reportId)));
    }

    @PostMapping("/encrypt")
    public Mono<Result<String>> encryptInEnclave(@RequestBody EncryptRequest request) {
        return Mono.just(Result.success(teeEnclaveService.encryptInEnclave(request)));
    }

    @PostMapping("/decrypt")
    public Mono<Result<String>> decryptInEnclave(@RequestBody Map<String, String> request) {
        return Mono.just(Result.success(teeEnclaveService.decryptInEnclave(
                request.get("enclaveId"),
                request.get("encryptedData"),
                request.get("keyId"))));
    }

    @PostMapping("/enclaves/{enclaveId}/execute/{functionName}")
    public Mono<Result<Map<String, Object>>> executeSecureFunction(
            @PathVariable String enclaveId,
            @PathVariable String functionName,
            @RequestBody(required = false) Map<String, Object> params) {
        return Mono.just(Result.success(teeEnclaveService.executeSecureFunction(enclaveId, functionName, params)));
    }

    @PostMapping("/enclaves/{enclaveId}/health-check")
    public Mono<Result<TeeEnclave>> healthCheck(@PathVariable String enclaveId) {
        return Mono.just(Result.success(teeEnclaveService.healthCheck(enclaveId)));
    }
}
