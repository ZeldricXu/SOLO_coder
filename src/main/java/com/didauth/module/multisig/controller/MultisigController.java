package com.didauth.module.multisig.controller;

import com.didauth.common.response.ApiResponse;
import com.didauth.core.entity.MultisigProposal;
import com.didauth.core.entity.MultisigWallet;
import com.didauth.module.multisig.dto.CreateMultisigWalletRequest;
import com.didauth.module.multisig.dto.CreateProposalRequest;
import com.didauth.module.multisig.dto.SubmitSignatureRequest;
import com.didauth.module.multisig.service.MultisigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/v1/multisig")
@RequiredArgsConstructor
public class MultisigController {

    private final MultisigService multisigService;

    @PostMapping("/wallets")
    public Mono<ApiResponse<String>> createWallet(@Valid @RequestBody CreateMultisigWalletRequest request) {
        return multisigService.createMultisigWallet(request)
                .map(id -> ApiResponse.success(201, id));
    }

    @GetMapping("/wallets")
    public Mono<ApiResponse<List<MultisigWallet>>> listWallets(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String chainType) {
        return multisigService.listWallets(userId, chainType)
                .map(ApiResponse::success);
    }

    @GetMapping("/wallets/{walletId}")
    public Mono<ApiResponse<MultisigWallet>> getWallet(@PathVariable String walletId) {
        return multisigService.getWallet(walletId)
                .map(ApiResponse::success);
    }

    @PostMapping("/proposals")
    public Mono<ApiResponse<String>> createProposal(@Valid @RequestBody CreateProposalRequest request) {
        return multisigService.createProposal(request)
                .map(id -> ApiResponse.success(201, id));
    }

    @PostMapping("/proposals/sign")
    public Mono<ApiResponse<String>> submitSignature(@Valid @RequestBody SubmitSignatureRequest request) {
        return multisigService.submitSignature(request)
                .map(ApiResponse::success);
    }

    @PostMapping("/proposals/{proposalId}/execute")
    public Mono<ApiResponse<String>> executeProposal(@PathVariable String proposalId) {
        return multisigService.executeProposal(proposalId)
                .map(ApiResponse::success);
    }

    @GetMapping("/proposals")
    public Mono<ApiResponse<List<MultisigProposal>>> listProposals(
            @RequestParam(required = false) String walletId,
            @RequestParam(required = false) String status) {
        return multisigService.listProposals(walletId, status)
                .map(ApiResponse::success);
    }

    @GetMapping("/proposals/{proposalId}")
    public Mono<ApiResponse<MultisigProposal>> getProposal(@PathVariable String proposalId) {
        return multisigService.getProposal(proposalId)
                .map(ApiResponse::success);
    }
}
