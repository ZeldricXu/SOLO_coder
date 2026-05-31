package com.web3platform.multisigwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.web3platform.multisigwallet.model.ProposalCreateRequest;
import com.web3platform.persistence.mapper.MultisigProposalMapper;
import com.web3platform.persistence.model.entity.MultisigProposal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProposalService {

    private final MultisigProposalMapper proposalMapper;
    private final MultisigWalletService walletService;

    @Transactional
    public MultisigProposal createProposal(ProposalCreateRequest request) {
        log.info("Creating proposal for wallet: {}, type: {}", request.getWalletAddress(), request.getProposalType());

        var wallet = walletService.getWallet(request.getWalletAddress());
        if (wallet == null) {
            throw new IllegalArgumentException("Wallet not found: " + request.getWalletAddress());
        }

        MultisigProposal proposal = new MultisigProposal();
        proposal.setWalletAddress(request.getWalletAddress());
        proposal.setProposalType(request.getProposalType());
        proposal.setTargetAddress(request.getTargetAddress());
        proposal.setValue(request.getValue() != null ? new BigDecimal(request.getValue()) : BigDecimal.ZERO);
        proposal.setData(request.getData());
        proposal.setStatus("PENDING");
        proposal.setThreshold(wallet.getThreshold());
        proposal.setCreatedAt(LocalDateTime.now());
        proposal.setUpdatedAt(LocalDateTime.now());

        proposalMapper.insert(proposal);
        log.info("Proposal created with id: {}", proposal.getId());
        return proposal;
    }

    public MultisigProposal getProposal(Long proposalId) {
        return proposalMapper.selectById(proposalId);
    }

    public Page<MultisigProposal> listProposals(String walletAddress, String status, int page, int size) {
        LambdaQueryWrapper<MultisigProposal> wrapper = new LambdaQueryWrapper<>();
        if (walletAddress != null) {
            wrapper.eq(MultisigProposal::getWalletAddress, walletAddress);
        }
        if (status != null) {
            wrapper.eq(MultisigProposal::getStatus, status);
        }
        wrapper.orderByDesc(MultisigProposal::getCreatedAt);
        return proposalMapper.selectPage(new Page<>(page, size), wrapper);
    }

    @Transactional
    public boolean cancelProposal(Long proposalId, String caller) {
        MultisigProposal proposal = proposalMapper.selectById(proposalId);
        if (proposal == null) {
            throw new IllegalArgumentException("Proposal not found: " + proposalId);
        }
        if (!"PENDING".equals(proposal.getStatus())) {
            throw new IllegalStateException("Only PENDING proposals can be cancelled");
        }

        var wallet = walletService.getWallet(proposal.getWalletAddress());
        if (wallet == null || !wallet.getOwners().contains(caller.toLowerCase())) {
            throw new SecurityException("Caller is not an owner of this wallet");
        }

        proposal.setStatus("CANCELLED");
        proposal.setUpdatedAt(LocalDateTime.now());
        proposalMapper.updateById(proposal);
        log.info("Proposal {} cancelled by {}", proposalId, caller);
        return true;
    }
}
