package com.chainetl.modules.multisig.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chainetl.modules.multisig.mapper.MultisigProposalMapper;
import com.chainetl.modules.multisig.mapper.MultisigSignatureMapper;
import com.chainetl.modules.multisig.model.MultisigProposal;
import com.chainetl.modules.multisig.model.MultisigSignature;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
@RequiredArgsConstructor
public class MultisigCacheWarmer {

    private final MultisigProposalMapper proposalMapper;
    private final MultisigSignatureMapper signatureMapper;
    private final Cache<String, Object> caffeineCache;
    private final ReactiveRedisTemplate<String, Object> reactiveRedisTemplate;

    private static final String L1_PROPOSAL_PREFIX = "multisig:proposal:";
    private static final String L1_SIGNATURE_PREFIX = "multisig:signatures:";
    private static final String L2_PROPOSAL_KEY = "multisig:proposals:l2";
    private static final String L2_SIGNATURE_KEY = "multisig:signatures:l2";
    private static final Duration L2_TTL = Duration.ofMinutes(30);

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("Multisig cache warming started at startup");
        CompletableFuture.runAsync(this::warmPendingProposals)
                .thenRun(() -> log.info("Multisig cache warming completed at startup"));
    }

    @Scheduled(fixedRateString = "${cache.multisig.warm-interval-ms:300000}", initialDelay = 120000)
    public void warmActiveProposals() {
        log.debug("Scheduled multisig cache warming started");
        warmPendingProposals();
        log.debug("Scheduled multisig cache warming completed");
    }

    private void warmPendingProposals() {
        try {
            LambdaQueryWrapper<MultisigProposal> wrapper = new LambdaQueryWrapper<>();
            wrapper.in(MultisigProposal::getStatus, "PENDING", "READY")
                    .orderByAsc(MultisigProposal::getCreatedAt)
                    .last("LIMIT 100");

            List<MultisigProposal> proposals = proposalMapper.selectList(wrapper);
            log.info("Warming {} active proposals into cache", proposals.size());

            for (MultisigProposal proposal : proposals) {
                String l1Key = L1_PROPOSAL_PREFIX + proposal.getProposalId();
                caffeineCache.put(l1Key, proposal);

                reactiveRedisTemplate.opsForValue()
                        .set(L2_PROPOSAL_KEY + ":" + proposal.getProposalId(),
                                proposal, L2_TTL)
                        .onErrorResume(e -> {
                            log.warn("Failed to warm L2 cache for proposal {}: {}",
                                    proposal.getProposalId(), e.getMessage());
                            return Mono.empty();
                        })
                        .subscribe();
            }

            proposals.parallelStream().forEach(p -> {
                LambdaQueryWrapper<MultisigSignature> sigWrapper = new LambdaQueryWrapper<>();
                sigWrapper.eq(MultisigSignature::getProposalId, p.getProposalId())
                        .orderByAsc(MultisigSignature::getSignedAt);
                List<MultisigSignature> sigs = signatureMapper.selectList(sigWrapper);
                if (!sigs.isEmpty()) {
                    caffeineCache.put(L1_SIGNATURE_PREFIX + p.getProposalId(), sigs);
                }
            });

        } catch (Exception e) {
            log.warn("Multisig cache warming failed: {}", e.getMessage());
        }
    }

    public void invalidateProposal(String proposalId) {
        String l1Key = L1_PROPOSAL_PREFIX + proposalId;
        caffeineCache.invalidate(l1Key);

        reactiveRedisTemplate.delete(L2_PROPOSAL_KEY + ":" + proposalId)
                .onErrorResume(e -> Mono.empty())
                .subscribe();
    }

    public void invalidateSignatures(String proposalId) {
        String l1Key = L1_SIGNATURE_PREFIX + proposalId;
        caffeineCache.invalidate(l1Key);

        reactiveRedisTemplate.delete(L2_SIGNATURE_KEY + ":" + proposalId)
                .onErrorResume(e -> Mono.empty())
                .subscribe();
    }

    public void invalidateProposalList() {
        reactiveRedisTemplate.keys("multisig:list:l2*")
                .flatMap(keys -> {
                    if (!keys.isEmpty()) {
                        return reactiveRedisTemplate.delete(keys);
                    }
                    return Mono.just(0L);
                })
                .onErrorResume(e -> Mono.empty())
                .subscribe();
    }

    public MultisigProposal getProposalFromL1(String proposalId) {
        return (MultisigProposal) caffeineCache.getIfPresent(L1_PROPOSAL_PREFIX + proposalId);
    }

    @SuppressWarnings("unchecked")
    public List<MultisigSignature> getSignaturesFromL1(String proposalId) {
        return (List<MultisigSignature>) caffeineCache.getIfPresent(L1_SIGNATURE_PREFIX + proposalId);
    }

    public void putProposalToL1(MultisigProposal proposal) {
        caffeineCache.put(L1_PROPOSAL_PREFIX + proposal.getProposalId(), proposal);
    }

    public void putSignaturesToL1(String proposalId, List<MultisigSignature> signatures) {
        caffeineCache.put(L1_SIGNATURE_PREFIX + proposalId, signatures);
    }

    public Mono<Boolean> warmProposalToL2(MultisigProposal proposal) {
        return reactiveRedisTemplate.opsForValue()
                .set(L2_PROPOSAL_KEY + ":" + proposal.getProposalId(),
                        proposal, L2_TTL);
    }
}
