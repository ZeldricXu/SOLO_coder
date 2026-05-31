package com.delivery.tracker.controller;

import com.delivery.tracker.common.Result;
import com.delivery.tracker.entity.TeeEnclave;
import com.delivery.tracker.service.TEEService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/tee")
@RequiredArgsConstructor
public class TEEController {

    private final TEEService teeService;

    @PostMapping("/enclaves")
    public Mono<Result<TeeEnclave>> createEnclave() {
        return teeService.createEnclave()
                .map(Result::success);
    }

    @GetMapping("/enclaves")
    public Mono<Result<List<TeeEnclave>>> getAllEnclaves() {
        return teeService.getAllEnclaves()
                .collectList()
                .map(Result::success);
    }

    @GetMapping("/enclaves/{enclaveId}")
    public Mono<Result<TeeEnclave>> getEnclave(@PathVariable String enclaveId) {
        return teeService.getEnclave(enclaveId)
                .map(Result::success);
    }

    @PostMapping("/enclaves/{enclaveId}/verify")
    public Mono<Result<Map<String, Object>>> verifyAttestation(
            @PathVariable String enclaveId,
            @RequestBody Map<String, String> request) {
        String challenge = request.get("challenge");
        return teeService.verifyAttestation(enclaveId, challenge)
                .map(valid -> Result.success(Map.of(
                        "enclaveId", enclaveId,
                        "valid", valid
                )));
    }

    @PostMapping("/enclaves/{enclaveId}/encrypt")
    public Mono<Result<Map<String, String>>> encryptData(
            @PathVariable String enclaveId,
            @RequestBody Map<String, String> request) {
        String plaintext = request.get("plaintext");
        return teeService.encryptData(enclaveId, plaintext)
                .map(ciphertext -> Result.success(Map.of(
                        "ciphertext", ciphertext
                )));
    }

    @PostMapping("/enclaves/{enclaveId}/decrypt")
    public Mono<Result<Map<String, String>>> decryptData(
            @PathVariable String enclaveId,
            @RequestBody Map<String, String> request) {
        String ciphertext = request.get("ciphertext");
        return teeService.decryptData(enclaveId, ciphertext)
                .map(plaintext -> Result.success(Map.of(
                        "plaintext", plaintext
                )));
    }

    @PostMapping("/enclaves/{enclaveId}/health-check")
    public Mono<Result<TeeEnclave>> healthCheck(@PathVariable String enclaveId) {
        return teeService.healthCheck(enclaveId)
                .map(Result::success);
    }

    @DeleteMapping("/enclaves/{enclaveId}")
    public Mono<Result<Void>> destroyEnclave(@PathVariable String enclaveId) {
        return teeService.destroyEnclave(enclaveId)
                .then(Mono.just(Result.success()));
    }
}
