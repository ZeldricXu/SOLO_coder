package com.didauth.module.multisig.service;

import com.didauth.common.enums.ChainType;
import com.didauth.common.exception.BusinessException;
import com.didauth.core.entity.MultisigProposal;
import com.didauth.core.entity.MultisigWallet;
import com.didauth.core.mapper.MultisigProposalMapper;
import com.didauth.core.mapper.MultisigWalletMapper;
import com.didauth.module.multisig.dto.CreateMultisigWalletRequest;
import com.didauth.module.multisig.dto.CreateProposalRequest;
import com.didauth.module.multisig.dto.SubmitSignatureRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.security.MessageDigest;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class MultisigService {

    private final MultisigWalletMapper multisigWalletMapper;
    private final MultisigProposalMapper multisigProposalMapper;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    public Mono<String> createMultisigWallet(CreateMultisigWalletRequest request) {
        return Mono.fromCallable(() -> {
            ChainType chainType = ChainType.fromCode(request.getChainType());

            if (request.getThreshold() > request.getSigners().size()) {
                throw BusinessException.paramError("Threshold cannot exceed signer count");
            }

            String walletId = "msig_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            String address = generateMultisigAddress(chainType, request.getSigners(), request.getThreshold());

            MultisigWallet wallet = new MultisigWallet();
            wallet.setWalletId(walletId);
            wallet.setChainType(chainType.getCode());
            wallet.setAddress(address);
            wallet.setThreshold(request.getThreshold());
            wallet.setSignerCount(request.getSigners().size());
            wallet.setSigners(objectMapper.writeValueAsString(request.getSigners()));
            wallet.setName(request.getName());
            wallet.setUserId(request.getUserId());
            wallet.setStatus("ACTIVE");

            multisigWalletMapper.insert(wallet);

            meterRegistry.counter("multisig.wallet.create.count", "chain", chainType.getCode()).increment();

            return walletId;
        });
    }

    private String generateMultisigAddress(ChainType chainType, List<String> signers, int threshold) throws Exception {
        Collections.sort(signers);
        String combined = String.join("", signers) + threshold;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(combined.getBytes());

        StringBuilder address = new StringBuilder();
        if (chainType == ChainType.BTC) {
            address.append("bc1");
        } else {
            address.append("0x");
        }
        for (int i = 0; i < 20; i++) {
            String hex = Integer.toHexString(0xff & hash[i]);
            if (hex.length() == 1) address.append('0');
            address.append(hex);
        }
        return address.toString();
    }

    @Transactional
    public Mono<String> createProposal(CreateProposalRequest request) {
        return Mono.fromCallable(() -> {
            MultisigWallet wallet = multisigWalletMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MultisigWallet>()
                            .eq(MultisigWallet::getWalletId, request.getWalletId()));

            if (wallet == null) {
                throw BusinessException.notFound("Multisig wallet not found: " + request.getWalletId());
            }

            String proposalId = "proposal_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

            MultisigProposal proposal = new MultisigProposal();
            proposal.setProposalId(proposalId);
            proposal.setWalletId(request.getWalletId());
            proposal.setChainType(wallet.getChainType());
            proposal.setToAddress(request.getToAddress());
            proposal.setValue(request.getValue());
            proposal.setData(request.getData());
            proposal.setNonce(request.getNonce());
            proposal.setThreshold(wallet.getThreshold());
            proposal.setTransactionData(buildTransactionData(request));
            proposal.setSignatures("[]");
            proposal.setSignerAddresses("[]");
            proposal.setSignedCount(0);
            proposal.setStatus("PENDING");

            multisigProposalMapper.insert(proposal);

            meterRegistry.counter("multisig.proposal.create.count", "chain", wallet.getChainType()).increment();

            return proposalId;
        });
    }

    private String buildTransactionData(CreateProposalRequest request) {
        Map<String, Object> txData = new HashMap<>();
        txData.put("to", request.getToAddress());
        txData.put("value", request.getValue() != null ? request.getValue() : "0");
        txData.put("data", request.getData() != null ? request.getData() : "");
        txData.put("nonce", request.getNonce());
        try {
            return objectMapper.writeValueAsString(txData);
        } catch (Exception e) {
            return "";
        }
    }

    @Transactional
    public Mono<String> submitSignature(SubmitSignatureRequest request) {
        return Mono.fromCallable(() -> {
            MultisigProposal proposal = multisigProposalMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MultisigProposal>()
                            .eq(MultisigProposal::getProposalId, request.getProposalId()));

            if (proposal == null) {
                throw BusinessException.notFound("Proposal not found: " + request.getProposalId());
            }

            if (!"PENDING".equals(proposal.getStatus())) {
                throw BusinessException.paramError("Proposal is not in PENDING state");
            }

            MultisigWallet wallet = multisigWalletMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MultisigWallet>()
                            .eq(MultisigWallet::getWalletId, proposal.getWalletId()));

            List<String> signers = objectMapper.readValue(wallet.getSigners(), new TypeReference<List<String>>() {});
            if (!signers.contains(request.getSignerAddress())) {
                throw BusinessException.paramError("Signer is not authorized for this wallet");
            }

            List<String> signatures = objectMapper.readValue(
                    proposal.getSignatures() != null ? proposal.getSignatures() : "[]",
                    new TypeReference<List<String>>() {});
            List<String> signerAddresses = objectMapper.readValue(
                    proposal.getSignerAddresses() != null ? proposal.getSignerAddresses() : "[]",
                    new TypeReference<List<String>>() {});

            if (signerAddresses.contains(request.getSignerAddress())) {
                throw BusinessException.paramError("Signature already submitted by this signer");
            }

            signatures.add(request.getSignature());
            signerAddresses.add(request.getSignerAddress());

            proposal.setSignatures(objectMapper.writeValueAsString(signatures));
            proposal.setSignerAddresses(objectMapper.writeValueAsString(signerAddresses));
            proposal.setSignedCount(signatures.size());

            if (signatures.size() >= proposal.getThreshold()) {
                proposal.setStatus("APPROVED");
                log.info("Proposal {} reached threshold, status updated to APPROVED", request.getProposalId());
            }

            multisigProposalMapper.updateById(proposal);

            meterRegistry.counter("multisig.signature.submit.count", "chain", proposal.getChainType()).increment();

            return proposal.getStatus();
        });
    }

    public Mono<String> executeProposal(String proposalId) {
        return Mono.fromCallable(() -> {
            MultisigProposal proposal = multisigProposalMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MultisigProposal>()
                            .eq(MultisigProposal::getProposalId, proposalId));

            if (proposal == null) {
                throw BusinessException.notFound("Proposal not found: " + proposalId);
            }

            if (!"APPROVED".equals(proposal.getStatus())) {
                throw BusinessException.paramError("Proposal is not approved, current status: " + proposal.getStatus());
            }

            String txHash = "0x" + UUID.randomUUID().toString().replace("-", "");
            proposal.setTxHash(txHash);
            proposal.setStatus("EXECUTED");
            multisigProposalMapper.updateById(proposal);

            meterRegistry.counter("multisig.proposal.execute.count", "chain", proposal.getChainType()).increment();

            return txHash;
        });
    }

    public Mono<MultisigWallet> getWallet(String walletId) {
        return Mono.fromCallable(() -> {
            MultisigWallet wallet = multisigWalletMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MultisigWallet>()
                            .eq(MultisigWallet::getWalletId, walletId));
            if (wallet == null) {
                throw BusinessException.notFound("Wallet not found: " + walletId);
            }
            return wallet;
        });
    }

    public Mono<List<MultisigWallet>> listWallets(String userId, String chainType) {
        return Mono.fromCallable(() -> {
            var wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MultisigWallet>();
            if (userId != null) wrapper.eq(MultisigWallet::getUserId, userId);
            if (chainType != null) wrapper.eq(MultisigWallet::getChainType, chainType);
            wrapper.orderByDesc(MultisigWallet::getCreatedAt);
            return multisigWalletMapper.selectList(wrapper);
        });
    }

    public Mono<MultisigProposal> getProposal(String proposalId) {
        return Mono.fromCallable(() -> {
            MultisigProposal proposal = multisigProposalMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MultisigProposal>()
                            .eq(MultisigProposal::getProposalId, proposalId));
            if (proposal == null) {
                throw BusinessException.notFound("Proposal not found: " + proposalId);
            }
            return proposal;
        });
    }

    public Mono<List<MultisigProposal>> listProposals(String walletId, String status) {
        return Mono.fromCallable(() -> {
            var wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MultisigProposal>();
            if (walletId != null) wrapper.eq(MultisigProposal::getWalletId, walletId);
            if (status != null) wrapper.eq(MultisigProposal::getStatus, status);
            wrapper.orderByDesc(MultisigProposal::getCreatedAt);
            return multisigProposalMapper.selectList(wrapper);
        });
    }
}
