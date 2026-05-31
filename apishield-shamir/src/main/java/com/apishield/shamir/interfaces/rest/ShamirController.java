package com.apishield.shamir.interfaces.rest;

import com.apishield.common.dto.Result;
import com.apishield.shamir.api.ShamirFacade;
import com.apishield.shamir.api.dto.GenerateSharesRequest;
import com.apishield.shamir.api.dto.RecoverSecretRequest;
import com.apishield.shamir.domain.model.ShamirKeyShare;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/v1/shamir")
@RequiredArgsConstructor
public class ShamirController {

    private final ShamirFacade shamirFacade;

    @PostMapping("/generate")
    public Mono<Result<List<ShamirKeyShare>>> generateShares(@RequestBody GenerateSharesRequest request) {
        return Mono.just(Result.success(
            shamirFacade.generateShares(request.getSecret(), request.getThreshold(), 
                                        request.getTotalShares(), request.getKeyId())
        ));
    }

    @PostMapping("/recover")
    public Mono<Result<String>> recoverSecret(@RequestBody RecoverSecretRequest request) {
        return Mono.just(Result.success(
            shamirFacade.recoverSecret(request.getShares(), request.getThreshold())
        ));
    }

    @GetMapping("/shares/{id}")
    public Mono<Result<ShamirKeyShare>> getShare(@PathVariable String id) {
        return Mono.just(shamirFacade.findById(id)
                .map(Result::success)
                .orElseGet(() -> Result.error("NOT_FOUND", "分片不存在")));
    }

    @GetMapping("/keys/{keyId}/shares")
    public Mono<Result<List<ShamirKeyShare>>> getSharesByKeyId(@PathVariable String keyId) {
        return Mono.just(Result.success(shamirFacade.findByKeyId(keyId)));
    }

    @PostMapping("/shares/{id}/distribute")
    public Mono<Result<Void>> distributeShare(@PathVariable String id, @RequestBody java.util.Map<String, String> body) {
        shamirFacade.distributeShare(id, body.get("ownerId"));
        return Mono.just(Result.success(null));
    }

    @PostMapping("/shares/{id}/revoke")
    public Mono<Result<Void>> revokeShare(@PathVariable String id) {
        shamirFacade.revokeShare(id);
        return Mono.just(Result.success(null));
    }
}
