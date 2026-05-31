package com.web3platform.multisigwallet.service;

import com.web3platform.chaininteraction.model.SubmitResult;
import com.web3platform.chaininteraction.service.TransactionService;
import com.web3platform.multisigwallet.config.MultisigWalletConfig;
import com.web3platform.multisigwallet.model.ExecutionResult;
import com.web3platform.persistence.mapper.MultisigProposalMapper;
import com.web3platform.persistence.model.entity.MultisigProposal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionService {

    private final SignatureService signatureService;
    private final MultisigProposalMapper proposalMapper;
    private final TransactionService transactionService;
    private final MultisigWalletConfig walletConfig;
    private final GnosisSafeCompatibleService gnosisSafeService;

    @Transactional
    public ExecutionResult executeProposal(Long proposalId) {
        log.info("Executing proposal: {}", proposalId);

        MultisigProposal proposal = proposalMapper.selectById(proposalId);
        if (proposal == null) {
            return ExecutionResult.builder()
                    .success(false)
                    .error("Proposal not found: " + proposalId)
                    .build();
        }

        if (!"PENDING".equals(proposal.getStatus())) {
            return ExecutionResult.builder()
                    .success(false)
                    .error("Proposal is not in PENDING status")
                    .build();
        }

        if (!signatureService.hasEnoughSignatures(proposalId)) {
            return ExecutionResult.builder()
                    .success(false)
                    .error("Not enough signatures, required: " + proposal.getThreshold() +
                            ", current: " + signatureService.getSignatureCount(proposalId))
                    .build();
        }

        try {
            var signatures = signatureService.getSignatures(proposalId);
            String signatureBytes = signatures.stream()
                    .map(sig -> sig.getSignature())
                    .collect(Collectors.joining());

            String execData = gnosisSafeService.buildExecTransactionData(
                    proposal.getTargetAddress(),
                    proposal.getValue().toBigInteger(),
                    proposal.getData(),
                    signatureBytes,
                    proposal.getThreshold()
            );

            SubmitResult result = transactionService.submitTransaction(
                    walletConfig.getDefaultChain(),
                    execData
            );

            if (result.isSuccess()) {
                proposal.setStatus("EXECUTED");
                proposal.setUpdatedAt(LocalDateTime.now());
                proposalMapper.updateById(proposal);
                log.info("Proposal {} executed successfully, txHash: {}", proposalId, result.getTxHash());
            } else {
                proposal.setStatus("EXECUTION_FAILED");
                proposal.setUpdatedAt(LocalDateTime.now());
                proposalMapper.updateById(proposal);
                log.warn("Proposal {} execution failed: {}", proposalId, result.getError());
            }

            return ExecutionResult.builder()
                    .success(result.isSuccess())
                    .txHash(result.getTxHash())
                    .error(result.getError())
                    .build();

        } catch (Exception e) {
            log.error("Failed to execute proposal: {}", proposalId, e);
            proposal.setStatus("EXECUTION_FAILED");
            proposal.setUpdatedAt(LocalDateTime.now());
            proposalMapper.updateById(proposal);

            return ExecutionResult.builder()
                    .success(false)
                    .error(e.getMessage())
                    .build();
        }
    }

    public ExecutionResult dryRun(Long proposalId) {
        log.info("Dry running proposal: {}", proposalId);

        MultisigProposal proposal = proposalMapper.selectById(proposalId);
        if (proposal == null) {
            return ExecutionResult.builder()
                    .success(false)
                    .error("Proposal not found: " + proposalId)
                    .build();
        }

        try {
            var signatures = signatureService.getSignatures(proposalId);
            boolean hasEnough = signatures.size() >= proposal.getThreshold();

            if (!hasEnough) {
                return ExecutionResult.builder()
                        .success(false)
                        .error("Dry run: Not enough signatures, required: " + proposal.getThreshold() +
                                ", current: " + signatures.size())
                        .build();
            }

            log.info("Dry run check passed for proposal: {}", proposalId);
            return ExecutionResult.builder()
                    .success(true)
                    .txHash("dry_run_" + proposalId)
                    .error(null)
                    .build();

        } catch (Exception e) {
            log.error("Dry run failed for proposal: {}", proposalId, e);
            return ExecutionResult.builder()
                    .success(false)
                    .error("Dry run failed: " + e.getMessage())
                    .build();
        }
    }
}
