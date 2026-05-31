package com.web3platform.multisigwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.web3platform.multisigwallet.event.SignatureSubmittedEvent;
import com.web3platform.multisigwallet.model.SignatureSubmitRequest;
import com.web3platform.persistence.mapper.MultisigProposalMapper;
import com.web3platform.persistence.mapper.MultisigSignatureMapper;
import com.web3platform.persistence.model.entity.MultisigProposal;
import com.web3platform.persistence.model.entity.MultisigSignature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SignatureService {

    private final MultisigSignatureMapper signatureMapper;
    private final MultisigProposalMapper proposalMapper;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public MultisigSignature submitSignature(SignatureSubmitRequest request) {
        log.info("Submitting signature for proposal: {}, signer: {}", request.getProposalId(), request.getSignerAddress());

        MultisigProposal proposal = proposalMapper.selectById(request.getProposalId());
        if (proposal == null) {
            throw new IllegalArgumentException("Proposal not found: " + request.getProposalId());
        }
        if (!"PENDING".equals(proposal.getStatus())) {
            throw new IllegalStateException("Proposal is not in PENDING status");
        }

        LambdaQueryWrapper<MultisigSignature> existingWrapper = new LambdaQueryWrapper<>();
        existingWrapper.eq(MultisigSignature::getProposalId, request.getProposalId())
                .eq(MultisigSignature::getSignerAddress, request.getSignerAddress().toLowerCase());
        if (signatureMapper.selectCount(existingWrapper) > 0) {
            log.warn("Signature already exists for proposal: {}, signer: {}", request.getProposalId(), request.getSignerAddress());
            return signatureMapper.selectOne(existingWrapper);
        }

        MultisigSignature signature = new MultisigSignature();
        signature.setProposalId(request.getProposalId());
        signature.setSignerAddress(request.getSignerAddress().toLowerCase());
        signature.setSignature(request.getSignature());
        signature.setSignedAt(LocalDateTime.now());

        signatureMapper.insert(signature);
        log.info("Signature submitted successfully for proposal: {}", request.getProposalId());

        int signatureCount = getSignatureCount(request.getProposalId());
        eventPublisher.publishEvent(new SignatureSubmittedEvent(
                this, request.getProposalId(), signatureCount, proposal.getThreshold()));

        return signature;
    }

    public List<MultisigSignature> getSignatures(Long proposalId) {
        LambdaQueryWrapper<MultisigSignature> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MultisigSignature::getProposalId, proposalId)
                .orderByAsc(MultisigSignature::getSignedAt);
        return signatureMapper.selectList(wrapper);
    }

    public int getSignatureCount(Long proposalId) {
        LambdaQueryWrapper<MultisigSignature> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MultisigSignature::getProposalId, proposalId);
        return Math.toIntExact(signatureMapper.selectCount(wrapper));
    }

    public boolean hasEnoughSignatures(Long proposalId) {
        MultisigProposal proposal = proposalMapper.selectById(proposalId);
        if (proposal == null) {
            return false;
        }
        int count = getSignatureCount(proposalId);
        return count >= proposal.getThreshold();
    }
}
