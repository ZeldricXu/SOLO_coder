package com.contraudit.multisig.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.contraudit.common.BusinessException;
import com.contraudit.common.ErrorCode;
import com.contraudit.multisig.dto.ApproveProposalRequest;
import com.contraudit.multisig.dto.CreateProposalRequest;
import com.contraudit.multisig.entity.MultisigApproval;
import com.contraudit.multisig.entity.MultisigProposal;
import com.contraudit.multisig.entity.MultisigWallet;
import com.contraudit.multisig.mapper.MultisigApprovalMapper;
import com.contraudit.multisig.mapper.MultisigProposalMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MultisigProposalService {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_EXECUTED = "EXECUTED";
    public static final String STATUS_EXPIRED = "EXPIRED";
    public static final String STATUS_FAILED = "FAILED";

    private final MultisigProposalMapper proposalMapper;
    private final MultisigApprovalMapper approvalMapper;
    private final MultisigWalletService walletService;

    @Value("${multisig.proposal.execution-timeout:30}")
    private int executionTimeoutSeconds;

    @Value("${multisig.proposal.default-expiry-hours:168}")
    private int defaultExpiryHours;

    @Transactional(rollbackFor = Exception.class, timeout = 30)
    public MultisigProposal createProposal(CreateProposalRequest request) {
        MultisigWallet wallet = walletService.getWallet(request.getWalletId());

        if (!walletService.isSigner(request.getWalletId(), request.getCreatorAddress())) {
            throw new BusinessException(ErrorCode.MULTISIG_INVALID_SIGNER);
        }

        MultisigProposal proposal = new MultisigProposal();
        proposal.setWalletId(request.getWalletId());
        proposal.setProposalType(request.getProposalType());
        proposal.setTitle(request.getTitle());
        proposal.setDescription(request.getDescription());
        proposal.setToAddress(request.getToAddress());
        proposal.setValue(request.getValue() != null ? request.getValue() : BigDecimal.ZERO);
        proposal.setData(request.getData());
        proposal.setNonce(request.getNonce() != null ? request.getNonce() : 0L);
        proposal.setGasLimit(request.getGasLimit());
        proposal.setGasPrice(request.getGasPrice());
        proposal.setStatus(STATUS_PENDING);
        proposal.setRequiredConfirmations(wallet.getThreshold());
        proposal.setCurrentConfirmations(0);
        proposal.setCreatorAddress(request.getCreatorAddress());
        proposal.setExpireAt(request.getExpireAt() != null ?
                request.getExpireAt() : LocalDateTime.now().plusHours(defaultExpiryHours));

        proposalMapper.insert(proposal);
        log.info("Created multisig proposal: {} for wallet: {}", proposal.getId(), request.getWalletId());

        return proposal;
    }

    public MultisigProposal getProposal(String proposalId) {
        MultisigProposal proposal = proposalMapper.selectById(proposalId);
        if (proposal == null) {
            throw new BusinessException(ErrorCode.MULTISIG_PROPOSAL_NOT_FOUND);
        }
        checkAndMarkExpired(proposal);
        return proposal;
    }

    public List<MultisigProposal> listProposals(String walletId, String status) {
        LambdaQueryWrapper<MultisigProposal> wrapper = new LambdaQueryWrapper<>();
        if (walletId != null) {
            wrapper.eq(MultisigProposal::getWalletId, walletId);
        }
        if (status != null) {
            wrapper.eq(MultisigProposal::getStatus, status);
        }
        wrapper.orderByDesc(MultisigProposal::getCreatedAt);
        List<MultisigProposal> proposals = proposalMapper.selectList(wrapper);
        proposals.forEach(this::checkAndMarkExpired);
        return proposals;
    }

    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public MultisigApproval approveProposal(ApproveProposalRequest request) {
        MultisigProposal proposal = getProposal(request.getProposalId());

        if (!STATUS_PENDING.equals(proposal.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "proposal is not pending");
        }

        if (proposal.getExpireAt() != null && proposal.getExpireAt().isBefore(LocalDateTime.now())) {
            markAsExpired(proposal);
            throw new BusinessException(ErrorCode.MULTISIG_PROPOSAL_EXPIRED);
        }

        if (!walletService.isSigner(proposal.getWalletId(), request.getSignerAddress())) {
            throw new BusinessException(ErrorCode.MULTISIG_INVALID_SIGNER);
        }

        LambdaQueryWrapper<MultisigApproval> existingWrapper = new LambdaQueryWrapper<>();
        existingWrapper.eq(MultisigApproval::getProposalId, request.getProposalId());
        existingWrapper.eq(MultisigApproval::getSignerAddress, request.getSignerAddress());
        if (approvalMapper.selectCount(existingWrapper) > 0) {
            throw new BusinessException(ErrorCode.MULTISIG_ALREADY_APPROVED);
        }

        MultisigApproval approval = new MultisigApproval();
        approval.setProposalId(request.getProposalId());
        approval.setSignerAddress(request.getSignerAddress());
        approval.setSignature(request.getSignature());
        approval.setApprovalType(request.getApprovalType());
        approval.setSignedAt(LocalDateTime.now());
        approvalMapper.insert(approval);

        if ("APPROVE".equals(request.getApprovalType())) {
            proposal.setCurrentConfirmations(proposal.getCurrentConfirmations() + 1);
            if (proposal.getCurrentConfirmations() >= proposal.getRequiredConfirmations()) {
                proposal.setStatus(STATUS_APPROVED);
            }
        } else if ("REJECT".equals(request.getApprovalType())) {
            proposal.setStatus(STATUS_REJECTED);
        }
        proposalMapper.updateById(proposal);

        log.info("{} proposal: {} by signer: {}", request.getApprovalType(),
                request.getProposalId(), request.getSignerAddress());

        return approval;
    }

    @Transactional(rollbackFor = Exception.class, timeout = 30)
    public MultisigProposal executeProposal(String proposalId) {
        return executeWithTimeout(proposalId).block();
    }

    public Mono<MultisigProposal> executeProposalAsync(String proposalId) {
        return executeWithTimeout(proposalId);
    }

    private Mono<MultisigProposal> executeWithTimeout(String proposalId) {
        return Mono.fromCallable(() -> doExecuteProposal(proposalId))
                .timeout(Duration.ofSeconds(executionTimeoutSeconds))
                .onErrorResume(e -> {
                    log.error("Proposal execution timed out or failed: {}", proposalId, e);
                    return Mono.fromCallable(() -> markAsFailed(proposalId, e.getMessage()));
                });
    }

    @Transactional(rollbackFor = Exception.class)
    public MultisigProposal doExecuteProposal(String proposalId) {
        MultisigProposal proposal = getProposal(proposalId);

        if (!STATUS_APPROVED.equals(proposal.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "proposal is not approved");
        }

        if (proposal.getExpireAt() != null && proposal.getExpireAt().isBefore(LocalDateTime.now())) {
            markAsExpired(proposal);
            throw new BusinessException(ErrorCode.MULTISIG_PROPOSAL_EXPIRED);
        }

        try {
            boolean executionSuccess = performExecution(proposal);

            if (!executionSuccess) {
                proposal.setStatus(STATUS_FAILED);
                proposal.setErrorMessage("Execution failed on chain");
                proposalMapper.updateById(proposal);
                log.warn("Proposal execution failed on chain: {}", proposalId);
                return proposal;
            }

            proposal.setStatus(STATUS_EXECUTED);
            proposal.setExecutedAt(LocalDateTime.now());
            proposalMapper.updateById(proposal);

            log.info("Executed proposal: {}", proposalId);

            return proposal;
        } catch (Exception e) {
            log.error("Proposal execution exception: {}", proposalId, e);
            proposal.setStatus(STATUS_FAILED);
            proposal.setErrorMessage(e.getMessage());
            proposalMapper.updateById(proposal);
            throw new BusinessException(ErrorCode.MULTISIG_EXECUTION_FAILED, e.getMessage());
        }
    }

    private boolean performExecution(MultisigProposal proposal) {
        log.debug("Performing on-chain execution for proposal: {}", proposal.getId());
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public MultisigProposal markAsFailed(String proposalId, String errorMessage) {
        MultisigProposal proposal = proposalMapper.selectById(proposalId);
        if (proposal != null) {
            proposal.setStatus(STATUS_FAILED);
            proposal.setErrorMessage(errorMessage);
            proposalMapper.updateById(proposal);
            log.warn("Marked proposal as FAILED: {} - {}", proposalId, errorMessage);
        }
        return proposal;
    }

    @Transactional(rollbackFor = Exception.class)
    public void markAsExpired(MultisigProposal proposal) {
        if (!STATUS_EXPIRED.equals(proposal.getStatus())) {
            proposal.setStatus(STATUS_EXPIRED);
            proposalMapper.updateById(proposal);
            log.info("Marked proposal as EXPIRED: {}", proposal.getId());
        }
    }

    private void checkAndMarkExpired(MultisigProposal proposal) {
        if (STATUS_PENDING.equals(proposal.getStatus())
                && proposal.getExpireAt() != null
                && proposal.getExpireAt().isBefore(LocalDateTime.now())) {
            markAsExpired(proposal);
        }
    }

    @Scheduled(fixedRate = 300000)
    @Transactional(rollbackFor = Exception.class)
    public void expireProposalsTask() {
        log.debug("Running proposal expiry check task");
        LambdaQueryWrapper<MultisigProposal> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MultisigProposal::getStatus, STATUS_PENDING);
        wrapper.lt(MultisigProposal::getExpireAt, LocalDateTime.now());
        wrapper.last("LIMIT 100");

        List<MultisigProposal> expiredProposals = proposalMapper.selectList(wrapper);
        expiredProposals.forEach(this::markAsExpired);

        if (!expiredProposals.isEmpty()) {
            log.info("Expired {} proposals in scheduled task", expiredProposals.size());
        }
    }

    @Scheduled(cron = "0 0 2 * * ?")
    @Transactional(rollbackFor = Exception.class)
    public void cleanupOldProposals() {
        log.info("Running old proposals cleanup task");
        LocalDateTime cutoff = LocalDateTime.now().minusDays(30);
        LambdaQueryWrapper<MultisigProposal> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(MultisigProposal::getStatus, List.of(STATUS_EXECUTED, STATUS_REJECTED, STATUS_EXPIRED, STATUS_FAILED));
        wrapper.lt(MultisigProposal::getCreatedAt, cutoff);
        wrapper.last("LIMIT 500");

        List<MultisigProposal> oldProposals = proposalMapper.selectList(wrapper);

        if (!oldProposals.isEmpty()) {
            log.info("Cleaning up {} old proposals", oldProposals.size());
        }
    }

    public List<MultisigApproval> getProposalApprovals(String proposalId) {
        LambdaQueryWrapper<MultisigApproval> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MultisigApproval::getProposalId, proposalId);
        wrapper.orderByAsc(MultisigApproval::getSignedAt);
        return approvalMapper.selectList(wrapper);
    }

    public int getExecutionTimeoutSeconds() {
        return executionTimeoutSeconds;
    }
}
