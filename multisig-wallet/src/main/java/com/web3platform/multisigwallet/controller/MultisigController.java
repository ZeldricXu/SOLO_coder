package com.web3platform.multisigwallet.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.web3platform.catalog.application.dto.PagedResult;
import com.web3platform.multisigwallet.model.*;
import com.web3platform.multisigwallet.service.*;
import com.web3platform.persistence.model.entity.MultisigProposal;
import com.web3platform.persistence.model.entity.MultisigSignature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/multisig")
@RequiredArgsConstructor
public class MultisigController {

    private final ProposalService proposalService;
    private final SignatureService signatureService;
    private final ExecutionService executionService;
    private final MultisigWalletService walletService;

    @PostMapping("/proposal")
    public ResponseEntity<MultisigProposal> createProposal(@RequestBody ProposalCreateRequest request) {
        log.info("API: Create proposal for wallet: {}", request.getWalletAddress());
        MultisigProposal proposal = proposalService.createProposal(request);
        return ResponseEntity.ok(proposal);
    }

    @GetMapping("/proposal/{proposalId}")
    public ResponseEntity<ProposalDetail> getProposal(@PathVariable Long proposalId) {
        log.info("API: Get proposal: {}", proposalId);
        MultisigProposal proposal = proposalService.getProposal(proposalId);
        if (proposal == null) {
            return ResponseEntity.notFound().build();
        }
        List<MultisigSignature> signatures = signatureService.getSignatures(proposalId);
        boolean canExecute = signatureService.hasEnoughSignatures(proposalId) && "PENDING".equals(proposal.getStatus());
        int executedCount = signatures.size();

        ProposalDetail detail = ProposalDetail.builder()
                .proposal(proposal)
                .signatures(signatures)
                .canExecute(canExecute)
                .executedCount(executedCount)
                .build();
        return ResponseEntity.ok(detail);
    }

    @GetMapping("/proposal/list")
    public ResponseEntity<Page<MultisigProposal>> listProposals(
            @RequestParam(required = false) String walletAddress,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        log.info("API: List proposals - wallet: {}, status: {}, page: {}, size: {}", walletAddress, status, page, size);
        Page<MultisigProposal> result = proposalService.listProposals(walletAddress, status, page, size);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/signature")
    public ResponseEntity<MultisigSignature> submitSignature(@RequestBody SignatureSubmitRequest request) {
        log.info("API: Submit signature for proposal: {}", request.getProposalId());
        MultisigSignature signature = signatureService.submitSignature(request);
        return ResponseEntity.ok(signature);
    }

    @PostMapping("/proposal/{proposalId}/execute")
    public ResponseEntity<ExecutionResult> executeProposal(@PathVariable Long proposalId) {
        log.info("API: Execute proposal: {}", proposalId);
        ExecutionResult result = executionService.executeProposal(proposalId);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/proposal/{proposalId}/dry-run")
    public ResponseEntity<ExecutionResult> dryRunProposal(@PathVariable Long proposalId) {
        log.info("API: Dry run proposal: {}", proposalId);
        ExecutionResult result = executionService.dryRun(proposalId);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/proposal/{proposalId}/cancel")
    public ResponseEntity<Map<String, Boolean>> cancelProposal(
            @PathVariable Long proposalId,
            @RequestParam String caller) {
        log.info("API: Cancel proposal: {} by {}", proposalId, caller);
        boolean success = proposalService.cancelProposal(proposalId, caller);
        return ResponseEntity.ok(Map.of("success", success));
    }

    @PostMapping("/wallet")
    public ResponseEntity<MultisigWallet> createWallet(@RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<String> owners = (List<String>) request.get("owners");
        int threshold = (int) request.get("threshold");
        String chainType = (String) request.getOrDefault("chainType", "ETH");
        log.info("API: Create wallet with {} owners, threshold: {}, chain: {}", owners.size(), threshold, chainType);
        MultisigWallet wallet = walletService.createWallet(owners, threshold, chainType);
        return ResponseEntity.ok(wallet);
    }

    @GetMapping("/wallet/{walletAddress}")
    public ResponseEntity<MultisigWallet> getWallet(@PathVariable String walletAddress) {
        log.info("API: Get wallet: {}", walletAddress);
        MultisigWallet wallet = walletService.getWallet(walletAddress);
        if (wallet == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(wallet);
    }

    @GetMapping("/wallet/list")
    public ResponseEntity<PagedResult<MultisigWallet>> listWallets(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        log.info("API: List wallets - page: {}, size: {}", page, size);
        PagedResult<MultisigWallet> result = walletService.listWallets(page, size);
        return ResponseEntity.ok(result);
    }

    @PutMapping("/wallet/{walletAddress}/threshold")
    public ResponseEntity<Map<String, Boolean>> updateThreshold(
            @PathVariable String walletAddress,
            @RequestParam int newThreshold) {
        log.info("API: Update threshold for wallet: {} to {}", walletAddress, newThreshold);
        boolean success = walletService.updateThreshold(walletAddress, newThreshold);
        return ResponseEntity.ok(Map.of("success", success));
    }
}
