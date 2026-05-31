package com.chain.infrastructure.multisig.controller;

import com.chain.infrastructure.common.dto.ApiResponse;
import com.chain.infrastructure.multisig.dto.CreateProposalRequest;
import com.chain.infrastructure.multisig.dto.CreateWalletRequest;
import com.chain.infrastructure.multisig.dto.SignProposalRequest;
import com.chain.infrastructure.multisig.service.MultisigWalletService;
import com.chain.infrastructure.persistence.entity.MultisigProposal;
import com.chain.infrastructure.persistence.entity.MultisigWallet;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/multisig")
@RequiredArgsConstructor
public class MultisigController {

    private final MultisigWalletService multisigWalletService;

    @PostMapping("/wallets")
    public Mono<ApiResponse<MultisigWallet>> createWallet(@RequestBody CreateWalletRequest request) {
        return multisigWalletService.createWallet(request)
                .map(ApiResponse::created);
    }

    @GetMapping("/wallets/{walletId}")
    public Mono<ApiResponse<MultisigWallet>> getWallet(@PathVariable String walletId) {
        return multisigWalletService.getWallet(walletId)
                .map(ApiResponse::success);
    }

    @GetMapping("/wallets/chain/{chainType}")
    public Flux<MultisigWallet> getWalletsByChain(@PathVariable String chainType) {
        return multisigWalletService.getWalletsByChain(chainType);
    }

    @PostMapping("/proposals")
    public Mono<ApiResponse<MultisigProposal>> createProposal(@RequestBody CreateProposalRequest request) {
        return multisigWalletService.createProposal(request)
                .map(ApiResponse::created);
    }

    @PostMapping("/proposals/{proposalId}/sign")
    public Mono<ApiResponse<MultisigProposal>> signProposal(@RequestBody SignProposalRequest request) {
        return multisigWalletService.signProposal(request)
                .map(ApiResponse::success);
    }

    @PostMapping("/proposals/{proposalId}/execute")
    public Mono<ApiResponse<MultisigProposal>> executeProposal(@PathVariable String proposalId) {
        return multisigWalletService.executeProposal(proposalId)
                .map(ApiResponse::success);
    }

    @PostMapping("/proposals/{proposalId}/reject")
    public Mono<ApiResponse<MultisigProposal>> rejectProposal(@PathVariable String proposalId) {
        return multisigWalletService.rejectProposal(proposalId)
                .map(ApiResponse::success);
    }

    @GetMapping("/wallets/{walletId}/proposals")
    public Flux<MultisigProposal> getProposalsByWallet(@PathVariable String walletId) {
        return multisigWalletService.getProposalsByWallet(walletId);
    }
}
