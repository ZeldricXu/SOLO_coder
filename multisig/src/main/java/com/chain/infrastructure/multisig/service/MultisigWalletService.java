package com.chain.infrastructure.multisig.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.chain.infrastructure.common.util.IdGenerator;
import com.chain.infrastructure.common.util.JsonUtils;
import com.chain.infrastructure.multisig.dto.CreateProposalRequest;
import com.chain.infrastructure.multisig.dto.CreateWalletRequest;
import com.chain.infrastructure.multisig.dto.SignProposalRequest;
import com.chain.infrastructure.persistence.entity.MultisigProposal;
import com.chain.infrastructure.persistence.entity.MultisigWallet;
import com.chain.infrastructure.persistence.mapper.MultisigProposalMapper;
import com.chain.infrastructure.persistence.mapper.MultisigWalletMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MultisigWalletService {

    private final MultisigWalletMapper walletMapper;
    private final MultisigProposalMapper proposalMapper;

    public Mono<MultisigWallet> createWallet(CreateWalletRequest request) {
        return Mono.fromCallable(() -> {
            String walletId = IdGenerator.generateId("msw");

            MultisigWallet wallet = new MultisigWallet();
            wallet.setWalletId(walletId);
            wallet.setChainType(request.getChainType());
            wallet.setWalletAddress(request.getWalletAddress());
            wallet.setThreshold(request.getThreshold());
            wallet.setOwners(JsonUtils.toJson(request.getOwners()));
            wallet.setName(request.getName());
            wallet.setDescription(request.getDescription());

            walletMapper.insert(wallet);

            log.info("Multisig wallet created: walletId={}, chain={}, threshold={}, owners={}",
                    walletId, request.getChainType(), request.getThreshold(), request.getOwners().size());

            return wallet;
        });
    }

    public Mono<MultisigWallet> getWallet(String walletId) {
        return Mono.fromCallable(() -> walletMapper.selectById(walletId));
    }

    public Flux<MultisigWallet> getWalletsByChain(String chainType) {
        return Flux.fromIterable(() -> {
            QueryWrapper<MultisigWallet> wrapper = new QueryWrapper<>();
            wrapper.eq("chain_type", chainType);
            return walletMapper.selectList(wrapper).iterator();
        });
    }

    public Mono<MultisigProposal> createProposal(CreateProposalRequest request) {
        return Mono.fromCallable(() -> {
            MultisigWallet wallet = walletMapper.selectById(request.getWalletId());
            if (wallet == null) {
                throw new IllegalArgumentException("Wallet not found: " + request.getWalletId());
            }

            String proposalId = IdGenerator.generateId("msp");

            MultisigProposal proposal = new MultisigProposal();
            proposal.setProposalId(proposalId);
            proposal.setWalletId(request.getWalletId());
            proposal.setProposer(request.getProposer());
            proposal.setTitle(request.getTitle());
            proposal.setDescription(request.getDescription());
            proposal.setTxData(request.getTxData());
            proposal.setStatus("PENDING");
            proposal.setSignatures("[]");
            proposal.setSignedCount(0);
            proposal.setThreshold(wallet.getThreshold());
            proposal.setExpiresAt(request.getExpiresAt() != null ? request.getExpiresAt() : LocalDateTime.now().plusDays(7));

            proposalMapper.insert(proposal);

            log.info("Multisig proposal created: proposalId={}, walletId={}, proposer={}",
                    proposalId, request.getWalletId(), request.getProposer());

            return proposal;
        });
    }

    public Mono<MultisigProposal> signProposal(SignProposalRequest request) {
        return Mono.fromCallable(() -> {
            MultisigProposal proposal = proposalMapper.selectById(request.getProposalId());
            if (proposal == null) {
                throw new IllegalArgumentException("Proposal not found: " + request.getProposalId());
            }

            if (!"PENDING".equals(proposal.getStatus())) {
                throw new IllegalStateException("Proposal is not pending: " + proposal.getStatus());
            }

            List<Map<String, String>> signatures = JsonUtils.fromJson(proposal.getSignatures(),
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, String>>>() {});

            boolean alreadySigned = signatures.stream()
                    .anyMatch(s -> request.getSigner().equals(s.get("signer")));

            if (alreadySigned) {
                throw new IllegalStateException("Signer has already signed this proposal");
            }

            Map<String, String> newSignature = new HashMap<>();
            newSignature.put("signer", request.getSigner());
            newSignature.put("signature", request.getSignature());
            newSignature.put("signedAt", LocalDateTime.now().toString());
            signatures.add(newSignature);

            proposal.setSignatures(JsonUtils.toJson(signatures));
            proposal.setSignedCount(signatures.size());

            if (signatures.size() >= proposal.getThreshold()) {
                proposal.setStatus("APPROVED");
                log.info("Proposal reached threshold: proposalId={}, signedCount={}",
                        request.getProposalId(), signatures.size());
            }

            proposalMapper.updateById(proposal);

            return proposal;
        });
    }

    public Mono<MultisigProposal> executeProposal(String proposalId) {
        return Mono.fromCallable(() -> {
            MultisigProposal proposal = proposalMapper.selectById(proposalId);
            if (proposal == null) {
                throw new IllegalArgumentException("Proposal not found: " + proposalId);
            }

            if (!"APPROVED".equals(proposal.getStatus())) {
                throw new IllegalStateException("Proposal is not approved: " + proposal.getStatus());
            }

            proposal.setStatus("EXECUTED");
            proposal.setExecutedAt(LocalDateTime.now());
            proposalMapper.updateById(proposal);

            log.info("Proposal executed: proposalId={}", proposalId);

            return proposal;
        });
    }

    public Mono<MultisigProposal> rejectProposal(String proposalId) {
        return Mono.fromCallable(() -> {
            MultisigProposal proposal = proposalMapper.selectById(proposalId);
            if (proposal == null) {
                throw new IllegalArgumentException("Proposal not found: " + proposalId);
            }

            proposal.setStatus("REJECTED");
            proposalMapper.updateById(proposal);

            log.info("Proposal rejected: proposalId={}", proposalId);

            return proposal;
        });
    }

    public Flux<MultisigProposal> getProposalsByWallet(String walletId) {
        return Flux.fromIterable(() -> {
            QueryWrapper<MultisigProposal> wrapper = new QueryWrapper<>();
            wrapper.eq("wallet_id", walletId)
                    .orderByDesc("created_at");
            return proposalMapper.selectList(wrapper).iterator();
        });
    }
}
