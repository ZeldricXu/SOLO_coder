package com.contraudit.multisig.controller;

import com.contraudit.common.ApiResponse;
import com.contraudit.multisig.dto.ApproveProposalRequest;
import com.contraudit.multisig.dto.CreateProposalRequest;
import com.contraudit.multisig.entity.MultisigApproval;
import com.contraudit.multisig.entity.MultisigProposal;
import com.contraudit.multisig.service.MultisigProposalService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/v1/multisig/proposals")
@RequiredArgsConstructor
public class MultisigProposalController {

    private final MultisigProposalService proposalService;

    @PostMapping
    public Mono<ApiResponse<MultisigProposal>> createProposal(@Valid @RequestBody CreateProposalRequest request) {
        return Mono.just(ApiResponse.created(proposalService.createProposal(request)));
    }

    @GetMapping("/{id}")
    public Mono<ApiResponse<MultisigProposal>> getProposal(@PathVariable String id) {
        return Mono.just(ApiResponse.success(proposalService.getProposal(id)));
    }

    @GetMapping
    public Mono<ApiResponse<List<MultisigProposal>>> listProposals(
            @RequestParam(required = false) String walletId,
            @RequestParam(required = false) String status) {
        return Mono.just(ApiResponse.success(proposalService.listProposals(walletId, status)));
    }

    @PostMapping("/approve")
    public Mono<ApiResponse<MultisigApproval>> approveProposal(@Valid @RequestBody ApproveProposalRequest request) {
        return Mono.just(ApiResponse.success(proposalService.approveProposal(request)));
    }

    @PostMapping("/{id}/execute")
    public Mono<ApiResponse<MultisigProposal>> executeProposal(@PathVariable String id) {
        return Mono.just(ApiResponse.success(proposalService.executeProposal(id)));
    }

    @PostMapping("/{id}/execute/async")
    public Mono<ApiResponse<MultisigProposal>> executeProposalAsync(@PathVariable String id) {
        return proposalService.executeProposalAsync(id)
                .map(ApiResponse::success);
    }

    @GetMapping("/{id}/approvals")
    public Mono<ApiResponse<List<MultisigApproval>>> getProposalApprovals(@PathVariable String id) {
        return Mono.just(ApiResponse.success(proposalService.getProposalApprovals(id)));
    }
}
