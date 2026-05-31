package com.chainetl.modules.multisig.controller;

import com.chainetl.common.dto.ApiResponse;
import com.chainetl.modules.multisig.dto.CreateProposalRequest;
import com.chainetl.modules.multisig.dto.ProposalDetailResponse;
import com.chainetl.modules.multisig.dto.SubmitSignatureRequest;
import com.chainetl.modules.multisig.service.MultisigService;
import io.micrometer.core.annotation.Timed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/multisig")
@RequiredArgsConstructor
public class MultisigController {

    private final MultisigService multisigService;

    @PostMapping("/proposals")
    @Timed(value = "multisig.proposal.create", description = "Time taken to create multisig proposal")
    public Mono<ResponseEntity<ApiResponse<ProposalDetailResponse>>> createProposal(
            @Valid @RequestBody CreateProposalRequest request) {
        return multisigService.createProposal(request)
                .map(proposal -> ResponseEntity.status(HttpStatus.CREATED)
                        .body(ApiResponse.success(201, proposal)));
    }

    @PostMapping("/signatures")
    @Timed(value = "multisig.signature.submit", description = "Time taken to submit signature")
    public Mono<ResponseEntity<ApiResponse<ProposalDetailResponse>>> submitSignature(
            @Valid @RequestBody SubmitSignatureRequest request) {
        return multisigService.submitSignature(request)
                .map(proposal -> ResponseEntity.ok(ApiResponse.success(proposal)));
    }

    @PostMapping("/proposals/{proposalId}/execute")
    @Timed(value = "multisig.proposal.execute", description = "Time taken to execute multisig proposal")
    public Mono<ResponseEntity<ApiResponse<ProposalDetailResponse>>> executeProposal(
            @PathVariable String proposalId) {
        return multisigService.executeProposal(proposalId)
                .map(proposal -> ResponseEntity.ok(ApiResponse.success(proposal)));
    }

    @PostMapping("/proposals/{proposalId}/reject")
    @Timed(value = "multisig.proposal.reject", description = "Time taken to reject multisig proposal")
    public Mono<ResponseEntity<ApiResponse<ProposalDetailResponse>>> rejectProposal(
            @PathVariable String proposalId) {
        return multisigService.rejectProposal(proposalId)
                .map(proposal -> ResponseEntity.ok(ApiResponse.success(proposal)));
    }

    @GetMapping("/proposals/{proposalId}")
    @Timed(value = "multisig.proposal.get", description = "Time taken to get multisig proposal")
    public Mono<ResponseEntity<ApiResponse<ProposalDetailResponse>>> getProposal(
            @PathVariable String proposalId) {
        return multisigService.getProposal(proposalId)
                .map(proposal -> ResponseEntity.ok(ApiResponse.success(proposal)));
    }

    @GetMapping("/proposals")
    @Timed(value = "multisig.proposal.list", description = "Time taken to list multisig proposals")
    public Mono<ResponseEntity<ApiResponse<List<ProposalDetailResponse>>>> listProposals(
            @RequestParam(required = false) String walletId,
            @RequestParam(required = false) String status) {
        return multisigService.listProposals(walletId, status)
                .map(proposals -> ResponseEntity.ok(ApiResponse.success(proposals)));
    }
}
