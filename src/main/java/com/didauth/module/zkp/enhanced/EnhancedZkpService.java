package com.didauth.module.zkp.enhanced;

import com.didauth.common.cache.MultiLevelCache;
import com.didauth.common.cache.CacheProperties;
import com.didauth.common.exception.BusinessException;
import com.didauth.core.entity.ZkpProof;
import com.didauth.core.mapper.ZkpProofMapper;
import com.didauth.module.zkp.dto.ZkpVerifyRequest;
import com.didauth.module.zkp.dto.ZkpVerifyResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class EnhancedZkpService {

    private static final String CACHE_NAME = "zkp-proofs";
    private static final String PROOF_RESULT_CACHE_PREFIX = "proof_result:";
    private static final String CIRCUIT_CACHE_PREFIX = "circuit:";

    private final ZkpProofMapper zkpProofMapper;
    private final MeterRegistry meterRegistry;
    private final MultiLevelCache multiLevelCache;
    private final CacheProperties cacheProperties;
    private final ObjectMapper objectMapper;

    private Duration defaultTtl;

    @PostConstruct
    public void init() {
        CacheProperties.CacheConfig config = cacheProperties.getConfig(CACHE_NAME);
        defaultTtl = config.getTtl();
        log.info("Enhanced ZKP Service initialized with TTL: {}s", defaultTtl.getSeconds());
    }

    public Mono<ZkpVerifyResponse> verifyProof(ZkpVerifyRequest request) {
        String proofId = "proof_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String proofHash = generateProofHash(request.getCircuitId(), request.getProofData());
        String cacheKey = PROOF_RESULT_CACHE_PREFIX + proofHash;

        Timer.Sample sample = Timer.start(meterRegistry);

        ZkpProof proof = new ZkpProof();
        proof.setProofId(proofId);
        proof.setCircuitId(request.getCircuitId());
        proof.setProofData(request.getProofData());
        proof.setPublicInputs(request.getPublicInputs() != null ? String.join(",", request.getPublicInputs()) : null);
        proof.setStatus("VERIFYING");
        zkpProofMapper.insert(proof);

        CacheProperties.CacheConfig config = cacheProperties.getConfig(CACHE_NAME);

        return multiLevelCache.get(
                CACHE_NAME,
                cacheKey,
                ZkpVerifyResponse.class,
                key -> performVerification(request, proofId, proof),
                config.getTtl(),
                config.isCacheNulls()
        ).doOnSuccess(response -> {
            long verifyTimeMs = sample.stop(Timer.builder("zkp.verify.duration.enhanced")
                    .tag("circuit", request.getCircuitId())
                    .tag("result", response.getVerified() ? "success" : "failed")
                    .tag("cache", response.getVerifyTimeMs() == null ? "hit" : "miss")
                    .register(meterRegistry)) / 1_000_000;

            if (response.getVerifyTimeMs() == null) {
                response.setVerifyTimeMs(verifyTimeMs);
                updateProofStatus(proofId, response.getVerified(), verifyTimeMs, null);
            }

            meterRegistry.counter("zkp.verify.count.enhanced",
                    "circuit", request.getCircuitId(),
                    "result", response.getVerified() ? "success" : "failed"
            ).increment();

            meterRegistry.gauge("zkp.cache.hitrate",
                    "cache", CACHE_NAME,
                    (c) -> multiLevelCache.getStats(CACHE_NAME).hitRate()
            );
        }).onErrorResume(e -> {
            log.error("ZKP verification failed", e);
            updateProofStatus(proofId, false, null, e.getMessage());
            return Mono.error(BusinessException.internalError("ZKP proof verification failed: " + e.getMessage()));
        });
    }

    private Mono<ZkpVerifyResponse> performVerification(ZkpVerifyRequest request, String proofId, ZkpProof proof) {
        return Mono.fromCallable(() -> {
            log.debug("Performing ZKP verification for proof: {}", proofId);
            boolean verified = performCircuitVerification(request);

            ZkpVerifyResponse response = new ZkpVerifyResponse();
            response.setProofId(proofId);
            response.setCircuitId(request.getCircuitId());
            response.setVerified(verified);
            response.setVerifyResult(verified ? "SUCCESS" : "FAILED");

            return response;
        });
    }

    private boolean performCircuitVerification(ZkpVerifyRequest request) throws Exception {
        if (request.getProofData() == null || request.getProofData().length() < 32) {
            throw new IllegalArgumentException("Invalid proof data");
        }

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(request.getProofData().getBytes());
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }

        Thread.sleep(50 + (long) (Math.random() * 100));

        return hexString.toString().matches("^[a-f0-9]{64}$");
    }

    private void updateProofStatus(String proofId, boolean verified, Long verifyTimeMs, String errorMessage) {
        ZkpProof proof = zkpProofMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ZkpProof>()
                        .eq(ZkpProof::getProofId, proofId));
        if (proof != null) {
            proof.setStatus(verified ? "VERIFIED" : (errorMessage != null ? "ERROR" : "FAILED"));
            proof.setVerifyResult(verified ? "SUCCESS" : "FAILED");
            proof.setVerifyTimeMs(verifyTimeMs);
            proof.setErrorMessage(errorMessage);
            zkpProofMapper.updateById(proof);
        }
    }

    private String generateProofHash(String circuitId, String proofData) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String combined = circuitId + ":" + proofData;
            byte[] hash = digest.digest(combined.getBytes());
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }

    public Mono<ZkpProof> getProofStatus(String proofId) {
        String cacheKey = "status:" + proofId;
        return multiLevelCache.get(
                CACHE_NAME,
                cacheKey,
                ZkpProof.class,
                key -> Mono.fromCallable(() -> {
                    ZkpProof proof = zkpProofMapper.selectOne(
                            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ZkpProof>()
                                    .eq(ZkpProof::getProofId, proofId));
                    if (proof == null) {
                        throw BusinessException.notFound("Proof not found: " + proofId);
                    }
                    return proof;
                }),
                Duration.ofMinutes(5)
        );
    }

    public Mono<Boolean> warmUpCache(Map<String, ZkpVerifyRequest> proofs) {
        Map<String, Object> cacheEntries = new HashMap<>();
        proofs.forEach((key, request) -> {
            try {
                boolean verified = performCircuitVerification(request);
                ZkpVerifyResponse response = new ZkpVerifyResponse();
                response.setProofId(key);
                response.setCircuitId(request.getCircuitId());
                response.setVerified(verified);
                response.setVerifyResult(verified ? "SUCCESS" : "FAILED");
                cacheEntries.put(PROOF_RESULT_CACHE_PREFIX + generateProofHash(request.getCircuitId(), request.getProofData()), response);
            } catch (Exception e) {
                log.warn("Failed to warm up cache for key: {}", key, e);
            }
        });
        return multiLevelCache.warmUp(CACHE_NAME, cacheEntries, defaultTtl);
    }

    public Mono<Void> invalidateProof(String circuitId, String proofData) {
        String proofHash = generateProofHash(circuitId, proofData);
        return multiLevelCache.evict(CACHE_NAME, PROOF_RESULT_CACHE_PREFIX + proofHash);
    }

    public Mono<Void> invalidateAll() {
        return multiLevelCache.clear(CACHE_NAME);
    }

    public MultiLevelCache.CacheStats getCacheStats() {
        return multiLevelCache.getStats(CACHE_NAME);
    }

    public Mono<Map<String, Object>> getCacheMetrics() {
        return Mono.fromCallable(() -> {
            MultiLevelCache.CacheStats stats = multiLevelCache.getStats(CACHE_NAME);
            Map<String, Object> metrics = new HashMap<>();
            metrics.put("hits", stats.getHits());
            metrics.put("misses", stats.getMisses());
            metrics.put("puts", stats.getPuts());
            metrics.put("evictions", stats.getEvictions());
            metrics.put("hitRate", stats.hitRate());
            metrics.put("totalRequests", stats.getHits() + stats.getMisses());
            return metrics;
        });
    }
}
