package com.apishield.shamir.controller;

import com.apishield.common.dto.Result;
import com.apishield.shamir.domain.ShamirKeyShare;
import com.apishield.shamir.dto.ShamirGenerateRequest;
import com.apishield.shamir.dto.ShamirRecoverRequest;
import com.apishield.shamir.service.ShamirService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/shamir")
@RequiredArgsConstructor
public class ShamirController {

    private final ShamirService shamirService;

    @PostMapping("/generate")
    public Mono<Result<List<ShamirKeyShare>>> generateShares(@RequestBody ShamirGenerateRequest request) {
        return Mono.just(Result.success(shamirService.generateShares(request)));
    }

    @PostMapping("/recover")
    public Mono<Result<String>> recoverSecret(@RequestBody ShamirRecoverRequest request) {
        return Mono.just(Result.success(shamirService.recoverSecret(request)));
    }

    @GetMapping("/shares/{id}")
    public Mono<Result<ShamirKeyShare>> getShare(@PathVariable String id) {
        return Mono.just(Result.success(shamirService.getShareById(id)));
    }

    @GetMapping("/keys/{keyId}/shares")
    public Mono<Result<List<ShamirKeyShare>>> getSharesByKeyId(@PathVariable String keyId) {
        return Mono.just(Result.success(shamirService.getSharesByKeyId(keyId)));
    }

    @PostMapping("/shares/{id}/distribute")
    public Mono<Result<Void>> distributeShare(@PathVariable String id, @RequestBody Map<String, String> body) {
        shamirService.distributeShare(id, body.get("ownerId"));
        return Mono.just(Result.success(null));
    }
}
