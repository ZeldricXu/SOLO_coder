package com.datamasker.interfaces.controller;

import com.datamasker.application.service.ShamirService;
import com.datamasker.domain.shamir.model.KeyShard;
import com.datamasker.domain.shamir.model.SecretRecoveryResult;
import com.datamasker.interfaces.dto.Result;
import com.datamasker.interfaces.dto.shamir.CreateSharesRequest;
import com.datamasker.interfaces.dto.shamir.CreateSharesResponse;
import com.datamasker.interfaces.dto.shamir.ReconstructRequest;
import com.datamasker.interfaces.dto.shamir.ReconstructResponse;
import com.datamasker.interfaces.assembler.ShamirAssembler;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/shamir")
@RequiredArgsConstructor
public class ShamirController {

    private final ShamirService shamirService;

    @PostMapping("/shares")
    public Result<CreateSharesResponse> createShares(@Valid @RequestBody CreateSharesRequest request) {
        List<KeyShard> shards = shamirService.createShares(
                request.getSecret(),
                request.getThreshold(),
                request.getTotalShares(),
                request.getOwner()
        );
        return Result.success(ShamirAssembler.toCreateSharesResponse(shards));
    }

    @PostMapping("/reconstruct")
    public Result<ReconstructResponse> reconstruct(@Valid @RequestBody ReconstructRequest request) {
        SecretRecoveryResult result = shamirService.reconstructSecret(
                request.getSecretId(),
                request.getShardIndices()
        );
        return Result.success(ShamirAssembler.toReconstructResponse(result));
    }

    @GetMapping("/shares/{secretId}")
    public Result<Map<String, Object>> getShardInfo(@PathVariable String secretId) {
        Map<String, Object> info = shamirService.getShardInfo(secretId);
        return Result.success(info);
    }
}
