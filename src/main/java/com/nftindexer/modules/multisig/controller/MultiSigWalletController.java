package com.nftindexer.modules.multisig.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nftindexer.common.ApiResponse;
import com.nftindexer.common.PageResult;
import com.nftindexer.entity.MultiSigProposal;
import com.nftindexer.entity.MultiSigSignature;
import com.nftindexer.entity.MultiSigWallet;
import com.nftindexer.modules.multisig.dto.ProposalCreateRequest;
import com.nftindexer.modules.multisig.dto.SignatureSubmitRequest;
import com.nftindexer.modules.multisig.dto.WalletCreateRequest;
import com.nftindexer.modules.multisig.service.MultiSigWalletService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/multisig")
@RequiredArgsConstructor
public class MultiSigWalletController {

    private final MultiSigWalletService multiSigService;

    @PostMapping("/wallets")
    public Mono<ApiResponse<MultiSigWallet>> createWallet(
            @Valid @RequestBody WalletCreateRequest request) {
        return multiSigService.createWallet(request)
                .map(wallet -> ApiResponse.created(wallet));
    }

    @GetMapping("/wallets/{walletId}")
    public Mono<ApiResponse<MultiSigWallet>> getWallet(@PathVariable String walletId) {
        return multiSigService.getWallet(walletId)
                .map(ApiResponse::success);
    }

    @GetMapping("/wallets")
    public Mono<ApiResponse<PageResult<MultiSigWallet>>> listWallets(
            @RequestParam(required = false) String chainId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return multiSigService.listWallets(chainId, status, pageNum, pageSize)
                .map(page -> ApiResponse.success(new PageResult<>(
                        page.getRecords(),
                        page.getTotal(),
                        page.getCurrent(),
                        page.getSize()
                )));
    }

    @PostMapping("/proposals")
    public Mono<ApiResponse<MultiSigProposal>> createProposal(
            @Valid @RequestBody ProposalCreateRequest request) {
        return multiSigService.createProposal(request)
                .map(proposal -> ApiResponse.created(proposal));
    }

    @GetMapping("/proposals/{proposalId}")
    public Mono<ApiResponse<MultiSigProposal>> getProposal(@PathVariable String proposalId) {
        return multiSigService.getProposal(proposalId)
                .map(ApiResponse::success);
    }

    @GetMapping("/proposals")
    public Mono<ApiResponse<PageResult<MultiSigProposal>>> listProposals(
            @RequestParam(required = false) String walletId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return multiSigService.listProposals(walletId, status, pageNum, pageSize)
                .map(page -> ApiResponse.success(new PageResult<>(
                        page.getRecords(),
                        page.getTotal(),
                        page.getCurrent(),
                        page.getSize()
                )));
    }

    @GetMapping("/proposals/{proposalId}/signatures")
    public Mono<ApiResponse<List<MultiSigSignature>>> getProposalSignatures(
            @PathVariable String proposalId) {
        return multiSigService.getProposalSignatures(proposalId)
                .map(ApiResponse::success);
    }

    @PostMapping("/signatures")
    public Mono<ApiResponse<MultiSigSignature>> submitSignature(
            @Valid @RequestBody SignatureSubmitRequest request) {
        return multiSigService.submitSignature(request)
                .map(ApiResponse::success);
    }

    @PostMapping("/proposals/{proposalId}/execute")
    public Mono<ApiResponse<MultiSigProposal>> executeProposal(
            @PathVariable String proposalId,
            @RequestBody Map<String, String> request) {
        String executedBy = request.getOrDefault("executedBy", "system");
        return multiSigService.executeProposal(proposalId, executedBy)
                .map(ApiResponse::success);
    }

    @PostMapping("/proposals/{proposalId}/reject")
    public Mono<ApiResponse<MultiSigProposal>> rejectProposal(
            @PathVariable String proposalId,
            @RequestBody Map<String, String> request) {
        String reason = request.getOrDefault("reason", "提案被拒绝");
        String rejectedBy = request.getOrDefault("rejectedBy", "system");
        return multiSigService.rejectProposal(proposalId, reason, rejectedBy)
                .map(ApiResponse::success);
    }
}
