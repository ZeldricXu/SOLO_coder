package com.chainetl.modules.multisig.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chainetl.common.exception.BusinessException;
import com.chainetl.common.util.IdGenerator;
import com.chainetl.modules.multisig.dto.CreateProposalRequest;
import com.chainetl.modules.multisig.dto.ProposalDetailResponse;
import com.chainetl.modules.multisig.dto.SubmitSignatureRequest;
import com.chainetl.modules.multisig.mapper.MultisigProposalMapper;
import com.chainetl.modules.multisig.mapper.MultisigSignatureMapper;
import com.chainetl.modules.multisig.model.MultisigProposal;
import com.chainetl.modules.multisig.model.MultisigSignature;
import com.github.benmanes.caffeine.cache.Cache;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MultisigService {

    private final MultisigProposalMapper proposalMapper;
    private final MultisigSignatureMapper signatureMapper;
    private final MultisigCacheWarmer cacheWarmer;
    private final Cache<String, Object> caffeineCache;
    private final ReactiveRedisTemplate<String, Object> reactiveRedisTemplate;

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_READY = "READY";
    private static final String STATUS_EXECUTED = "EXECUTED";
    private static final String STATUS_EXPIRED = "EXPIRED";
    private static final String STATUS_REJECTED = "REJECTED";

    private static final String L2_PROPOSAL_KEY = "multisig:proposals:l2";
    private static final String L2_SIGNATURE_KEY = "multisig:signatures:l2";
    private static final String L2_LIST_KEY = "multisig:list:l2";
    private static final Duration L2_TTL = Duration.ofMinutes(30);

    @Transactional
    @Retry(name = "multisig", fallbackMethod = "createProposalFallback")
    public Mono<ProposalDetailResponse> createProposal(CreateProposalRequest request) {
        return Mono.fromCallable(() -> {
            Instant now = Instant.now();
            String proposalId = IdGenerator.generateProposalId();

            MultisigProposal proposal = MultisigProposal.builder()
                    .proposalId(proposalId)
                    .walletId(request.getWalletId())
                    .chainId(request.getChainId())
                    .transactionData(request.getTransactionData())
                    .requiredSignatures(request.getRequiredSignatures())
                    .currentSignatures(0)
                    .status(STATUS_PENDING)
                    .proposer(request.getProposer())
                    .createdAt(now)
                    .expiresAt(request.getExpireSeconds() != null ?
                            now.plusSeconds(request.getExpireSeconds()) : null)
                    .build();

            proposalMapper.insert(proposal);
            log.info("Created multisig proposal: {}", proposalId);

            cacheWarmer.putProposalToL1(proposal);
            cacheWarmer.warmProposalToL2(proposal).subscribe();

            return buildDetailResponse(proposal, List.of());
        });
    }

    @Transactional
    @Retry(name = "multisig", fallbackMethod = "submitSignatureFallback")
    public Mono<ProposalDetailResponse> submitSignature(SubmitSignatureRequest request) {
        return Mono.fromCallable(() -> {
            MultisigProposal proposal = getProposalWithCache(request.getProposalId());
            if (proposal == null) {
                throw new BusinessException(404, "Proposal not found");
            }

            validateProposalStatus(proposal);

            LambdaQueryWrapper<MultisigSignature> existingWrapper = new LambdaQueryWrapper<>();
            existingWrapper.eq(MultisigSignature::getProposalId, request.getProposalId())
                    .eq(MultisigSignature::getSignerAddress, request.getSignerAddress());
            if (signatureMapper.selectCount(existingWrapper) > 0) {
                throw new BusinessException(400, "Signature already submitted by this signer");
            }

            MultisigSignature signature = MultisigSignature.builder()
                    .signatureId(IdGenerator.generateSignatureId())
                    .proposalId(request.getProposalId())
                    .signerAddress(request.getSignerAddress())
                    .signatureData(request.getSignatureData())
                    .signedAt(Instant.now())
                    .build();
            signatureMapper.insert(signature);

            int newCount = proposal.getCurrentSignatures() + 1;
            proposal.setCurrentSignatures(newCount);

            if (newCount >= proposal.getRequiredSignatures()) {
                proposal.setStatus(STATUS_READY);
            }
            proposalMapper.updateById(proposal);

            log.info("Submitted signature for proposal: {}, signer: {}",
                    request.getProposalId(), request.getSignerAddress());

            cacheWarmer.putProposalToL1(proposal);
            cacheWarmer.invalidateSignatures(request.getProposalId());
            cacheWarmer.warmProposalToL2(proposal).subscribe();

            List<MultisigSignature> signatures = getSignaturesForProposal(request.getProposalId());
            return buildDetailResponse(proposal, signatures);
        });
    }

    @Retry(name = "multisig", fallbackMethod = "executeProposalFallback")
    public Mono<ProposalDetailResponse> executeProposal(String proposalId) {
        return Mono.fromCallable(() -> {
            MultisigProposal proposal = getProposalWithCache(proposalId);
            if (proposal == null) {
                throw new BusinessException(404, "Proposal not found");
            }

            if (!STATUS_READY.equals(proposal.getStatus())) {
                throw new BusinessException(400, "Proposal is not ready for execution. Current status: " + proposal.getStatus());
            }

            if (proposal.getExpiresAt() != null && proposal.getExpiresAt().isBefore(Instant.now())) {
                proposal.setStatus(STATUS_EXPIRED);
                proposalMapper.updateById(proposal);
                cacheWarmer.invalidateProposal(proposalId);
                throw new BusinessException(400, "Proposal has expired");
            }

            proposal.setStatus(STATUS_EXECUTED);
            proposal.setExecutedAt(Instant.now());
            proposalMapper.updateById(proposal);

            log.info("Executed multisig proposal: {}", proposalId);

            cacheWarmer.invalidateProposal(proposalId);
            cacheWarmer.invalidateSignatures(proposalId);
            cacheWarmer.invalidateProposalList();

            List<MultisigSignature> signatures = getSignaturesForProposal(proposalId);
            return buildDetailResponse(proposal, signatures);
        });
    }

    @Cacheable(value = "multisigProposals", key = "#proposalId", unless = "#result == null")
    public Mono<ProposalDetailResponse> getProposal(String proposalId) {
        return Mono.fromCallable(() -> {
            MultisigProposal proposal = getProposalWithCache(proposalId);
            if (proposal == null) {
                throw new BusinessException(404, "Proposal not found");
            }
            List<MultisigSignature> signatures = getSignaturesForProposal(proposalId);
            return buildDetailResponse(proposal, signatures);
        });
    }

    public Mono<List<ProposalDetailResponse>> listProposals(String walletId, String status) {
        return Mono.fromCallable(() -> {
            String cacheKey = L2_LIST_KEY + ":" +
                    (walletId != null ? walletId : "all") + ":" +
                    (status != null ? status : "all");

            Object cached = caffeineCache.getIfPresent(cacheKey);
            if (cached != null) {
                log.debug("Cache hit for proposal list: {}", cacheKey);
                @SuppressWarnings("unchecked")
                List<ProposalDetailResponse> result = (List<ProposalDetailResponse>) cached;
                return result;
            }

            LambdaQueryWrapper<MultisigProposal> wrapper = new LambdaQueryWrapper<>();
            if (walletId != null) {
                wrapper.eq(MultisigProposal::getWalletId, walletId);
            }
            if (status != null) {
                wrapper.eq(MultisigProposal::getStatus, status);
            }
            wrapper.orderByDesc(MultisigProposal::getCreatedAt);

            List<MultisigProposal> proposals = proposalMapper.selectList(wrapper);
            List<ProposalDetailResponse> result = proposals.stream()
                    .map(p -> {
                        List<MultisigSignature> signatures = getSignaturesForProposal(p.getProposalId());
                        return buildDetailResponse(p, signatures);
                    })
                    .collect(Collectors.toList());

            caffeineCache.put(cacheKey, result);
            log.debug("Cached proposal list: {}", cacheKey);

            return result;
        });
    }

    @Transactional
    public Mono<ProposalDetailResponse> rejectProposal(String proposalId) {
        return Mono.fromCallable(() -> {
            MultisigProposal proposal = getProposalWithCache(proposalId);
            if (proposal == null) {
                throw new BusinessException(404, "Proposal not found");
            }
            proposal.setStatus(STATUS_REJECTED);
            proposalMapper.updateById(proposal);

            log.info("Rejected multisig proposal: {}", proposalId);

            cacheWarmer.invalidateProposal(proposalId);
            cacheWarmer.invalidateSignatures(proposalId);
            cacheWarmer.invalidateProposalList();

            List<MultisigSignature> signatures = getSignaturesForProposal(proposalId);
            return buildDetailResponse(proposal, signatures);
        });
    }

    private MultisigProposal getProposalWithCache(String proposalId) {
        MultisigProposal cached = cacheWarmer.getProposalFromL1(proposalId);
        if (cached != null) {
            log.debug("L1 cache hit for proposal: {}", proposalId);
            return cached;
        }

        try {
            Object l2Obj = reactiveRedisTemplate.opsForValue()
                    .get(L2_PROPOSAL_KEY + ":" + proposalId)
                    .block(Duration.ofSeconds(2));
            if (l2Obj != null) {
                MultisigProposal l2Proposal = (MultisigProposal) l2Obj;
                log.debug("L2 cache hit for proposal: {}", proposalId);
                cacheWarmer.putProposalToL1(l2Proposal);
                return l2Proposal;
            }
        } catch (Exception e) {
            log.debug("L2 cache access failed: {}", e.getMessage());
        }

        log.debug("Cache miss for proposal: {}", proposalId);
        MultisigProposal proposal = proposalMapper.selectById(proposalId);
        if (proposal != null) {
            cacheWarmer.putProposalToL1(proposal);
            cacheWarmer.warmProposalToL2(proposal).subscribe();
        }
        return proposal;
    }

    private void validateProposalStatus(MultisigProposal proposal) {
        if (STATUS_EXECUTED.equals(proposal.getStatus())) {
            throw new BusinessException(400, "Proposal already executed");
        }
        if (STATUS_REJECTED.equals(proposal.getStatus())) {
            throw new BusinessException(400, "Proposal already rejected");
        }
        if (proposal.getExpiresAt() != null && proposal.getExpiresAt().isBefore(Instant.now())) {
            proposal.setStatus(STATUS_EXPIRED);
            proposalMapper.updateById(proposal);
            cacheWarmer.invalidateProposal(proposal.getProposalId());
            throw new BusinessException(400, "Proposal has expired");
        }
    }

    private List<MultisigSignature> getSignaturesForProposal(String proposalId) {
        List<MultisigSignature> cached = cacheWarmer.getSignaturesFromL1(proposalId);
        if (cached != null) {
            log.debug("L1 cache hit for signatures of proposal: {}", proposalId);
            return cached;
        }

        LambdaQueryWrapper<MultisigSignature> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MultisigSignature::getProposalId, proposalId)
                .orderByAsc(MultisigSignature::getSignedAt);
        List<MultisigSignature> sigs = signatureMapper.selectList(wrapper);

        if (!sigs.isEmpty()) {
            cacheWarmer.putSignaturesToL1(proposalId, sigs);
        }
        return sigs;
    }

    private ProposalDetailResponse buildDetailResponse(MultisigProposal proposal, List<MultisigSignature> signatures) {
        List<ProposalDetailResponse.SignatureInfo> signatureInfos = signatures.stream()
                .map(s -> ProposalDetailResponse.SignatureInfo.builder()
                        .signerAddress(s.getSignerAddress())
                        .signatureData(s.getSignatureData())
                        .signedAt(s.getSignedAt())
                        .build())
                .collect(Collectors.toList());

        return ProposalDetailResponse.builder()
                .proposalId(proposal.getProposalId())
                .walletId(proposal.getWalletId())
                .chainId(proposal.getChainId())
                .transactionData(proposal.getTransactionData())
                .requiredSignatures(proposal.getRequiredSignatures())
                .currentSignatures(proposal.getCurrentSignatures())
                .status(proposal.getStatus())
                .proposer(proposal.getProposer())
                .createdAt(proposal.getCreatedAt())
                .executedAt(proposal.getExecutedAt())
                .expiresAt(proposal.getExpiresAt())
                .signatures(signatureInfos)
                .build();
    }

    private Mono<ProposalDetailResponse> createProposalFallback(CreateProposalRequest request, Exception e) {
        log.error("Create proposal fallback triggered: {}", e.getMessage(), e);
        throw new BusinessException("Failed to create proposal after retries: " + e.getMessage());
    }

    private Mono<ProposalDetailResponse> submitSignatureFallback(SubmitSignatureRequest request, Exception e) {
        log.error("Submit signature fallback triggered: {}", e.getMessage(), e);
        throw new BusinessException("Failed to submit signature after retries: " + e.getMessage());
    }

    private Mono<ProposalDetailResponse> executeProposalFallback(String proposalId, Exception e) {
        log.error("Execute proposal fallback triggered: {}", e.getMessage(), e);
        throw new BusinessException("Failed to execute proposal after retries: " + e.getMessage());
    }
}
