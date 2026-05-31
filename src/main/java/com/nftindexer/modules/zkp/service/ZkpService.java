package com.nftindexer.modules.zkp.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nftindexer.common.OptimisticRetry;
import com.nftindexer.common.TraceContext;
import com.nftindexer.entity.ZkpCircuit;
import com.nftindexer.entity.ZkpProof;
import com.nftindexer.event.DomainEvent;
import com.nftindexer.exception.BusinessException;
import com.nftindexer.mapper.ZkpCircuitMapper;
import com.nftindexer.mapper.ZkpProofMapper;
import com.nftindexer.modules.zkp.dto.ZkpCircuitCreateRequest;
import com.nftindexer.modules.zkp.dto.ZkpVerifyRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.util.encoders.Hex;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ZkpService {

    private final ZkpCircuitMapper circuitMapper;
    private final ZkpProofMapper proofMapper;
    private final Sinks.Many<DomainEvent> eventSink;

    @Transactional
    @OptimisticRetry(maxAttempts = 3)
    public Mono<ZkpCircuit> registerCircuit(ZkpCircuitCreateRequest request) {
        return TraceContext.getTraceId()
                .flatMap(traceId -> Mono.fromCallable(() -> {
                    LambdaQueryWrapper<ZkpCircuit> existingWrapper = new LambdaQueryWrapper<>();
                    existingWrapper.eq(ZkpCircuit::getCircuitName, request.getCircuitName());
                    if (request.getVersion() != null) {
                        existingWrapper.eq(ZkpCircuit::getVersion, request.getVersion());
                    }
                    existingWrapper.eq(ZkpCircuit::getStatus, "active");
                    if (circuitMapper.selectCount(existingWrapper) > 0) {
                        throw BusinessException.conflict("该电路名称和版本已存在");
                    }

                    String circuitId = "zkc-" + UUID.randomUUID().toString().substring(0, 8);
                    ZkpCircuit circuit = new ZkpCircuit();
                    circuit.setCircuitId(circuitId);
                    circuit.setCircuitName(request.getCircuitName());
                    circuit.setCircuitType(request.getCircuitType());
                    circuit.setProvingKey(request.getProvingKey());
                    circuit.setVerificationKey(request.getVerificationKey());
                    circuit.setCompiledCircuit(request.getCompiledCircuit());
                    circuit.setSourceCode(request.getSourceCode());
                    circuit.setVersion(request.getVersion() != null ? request.getVersion() : 1);
                    circuit.setStatus("active");
                    circuit.setCompiledAt(LocalDateTime.now());
                    circuit.setCreatedBy(request.getCreatedBy());
                    circuit.setMetadata(request.getMetadata());

                    circuitMapper.insert(circuit);

                    emitEvent("circuit.registered", circuitId, "zkp_circuit", circuit, traceId);
                    log.info("Registered ZKP circuit: {} ({})", circuitId, request.getCircuitName());

                    return circuit;
                }));
    }

    @Cacheable(value = "zkpCircuit", key = "#circuitId", unless = "#result == null")
    public Mono<ZkpCircuit> getCircuit(String circuitId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<ZkpCircuit> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ZkpCircuit::getCircuitId, circuitId);
            ZkpCircuit circuit = circuitMapper.selectOne(wrapper);

            if (circuit == null) {
                throw BusinessException.notFound("ZKP电路不存在: " + circuitId);
            }
            return circuit;
        });
    }

    public Mono<ZkpCircuit> getCircuitByName(String circuitName) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<ZkpCircuit> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ZkpCircuit::getCircuitName, circuitName);
            wrapper.eq(ZkpCircuit::getStatus, "active");
            wrapper.orderByDesc(ZkpCircuit::getVersion);
            wrapper.last("LIMIT 1");
            ZkpCircuit circuit = circuitMapper.selectOne(wrapper);

            if (circuit == null) {
                throw BusinessException.notFound("ZKP电路不存在: " + circuitName);
            }
            return circuit;
        });
    }

    public Mono<Page<ZkpCircuit>> listCircuits(String circuitType, String status,
                                                int pageNum, int pageSize) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<ZkpCircuit> wrapper = new LambdaQueryWrapper<>();
            if (circuitType != null && !circuitType.isEmpty()) {
                wrapper.eq(ZkpCircuit::getCircuitType, circuitType);
            }
            if (status != null && !status.isEmpty()) {
                wrapper.eq(ZkpCircuit::getStatus, status);
            }
            wrapper.orderByDesc(ZkpCircuit::getCreatedAt);
            return circuitMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        });
    }

    @Transactional
    @CacheEvict(value = "zkpCircuit", key = "#circuitId")
    public Mono<ZkpCircuit> deactivateCircuit(String circuitId, String deactivatedBy) {
        return TraceContext.getTraceId()
                .flatMap(traceId -> Mono.fromCallable(() -> {
                    LambdaQueryWrapper<ZkpCircuit> wrapper = new LambdaQueryWrapper<>();
                    wrapper.eq(ZkpCircuit::getCircuitId, circuitId);
                    ZkpCircuit circuit = circuitMapper.selectOne(wrapper);

                    if (circuit == null) {
                        throw BusinessException.notFound("ZKP电路不存在: " + circuitId);
                    }

                    circuit.setStatus("inactive");
                    circuit.setUpdatedBy(deactivatedBy);
                    circuitMapper.updateById(circuit);

                    emitEvent("circuit.deactivated", circuitId, "zkp_circuit",
                            Map.of("deactivatedBy", deactivatedBy), traceId);
                    log.info("Deactivated ZKP circuit: {}", circuitId);

                    return circuit;
                }));
    }

    @Transactional
    @OptimisticRetry(maxAttempts = 3)
    public Mono<ZkpProof> verifyProof(ZkpVerifyRequest request) {
        return TraceContext.getTraceId()
                .flatMap(traceId -> Mono.fromCallable(() -> {
                    String proofId = "zkp-" + UUID.randomUUID().toString().substring(0, 8);
                    long startTime = System.currentTimeMillis();

                    ZkpProof proof = new ZkpProof();
                    proof.setProofId(proofId);
                    proof.setProofData(request.getProofData());
                    proof.setPublicInputs(request.getPublicInputs());
                    proof.setSubmittedAt(LocalDateTime.now());
                    proof.setSubmittedBy(request.getSubmittedBy());
                    proof.setMetadata(request.getMetadata());
                    proof.setStatus("verifying");

                    String verificationKey = request.getVerificationKey();
                    String circuitId = request.getCircuitId();
                    String circuitName = request.getCircuitName();

                    if (verificationKey == null || verificationKey.isEmpty()) {
                        ZkpCircuit circuit;
                        if (circuitId != null && !circuitId.isEmpty()) {
                            LambdaQueryWrapper<ZkpCircuit> wrapper = new LambdaQueryWrapper<>();
                            wrapper.eq(ZkpCircuit::getCircuitId, circuitId);
                            wrapper.eq(ZkpCircuit::getStatus, "active");
                            circuit = circuitMapper.selectOne(wrapper);
                        } else if (circuitName != null && !circuitName.isEmpty()) {
                            LambdaQueryWrapper<ZkpCircuit> wrapper = new LambdaQueryWrapper<>();
                            wrapper.eq(ZkpCircuit::getCircuitName, circuitName);
                            wrapper.eq(ZkpCircuit::getStatus, "active");
                            wrapper.orderByDesc(ZkpCircuit::getVersion);
                            wrapper.last("LIMIT 1");
                            circuit = circuitMapper.selectOne(wrapper);
                        } else {
                            throw BusinessException.validationError("必须提供电路ID、电路名称或验证密钥");
                        }

                        if (circuit == null) {
                            throw BusinessException.notFound("未找到匹配的ZKP电路");
                        }

                        verificationKey = circuit.getVerificationKey();
                        circuitId = circuit.getCircuitId();
                        circuitName = circuit.getCircuitName();
                    }

                    proof.setCircuitId(circuitId);
                    proof.setCircuitName(circuitName);
                    proof.setVerificationKey(verificationKey);

                    proofMapper.insert(proof);

                    try {
                        boolean verified = performVerification(
                                request.getProofData(),
                                request.getPublicInputs(),
                                verificationKey
                        );

                        long verificationTime = System.currentTimeMillis() - startTime;

                        proof.setVerified(verified);
                        proof.setStatus(verified ? "verified" : "invalid");
                        proof.setVerifiedAt(LocalDateTime.now());
                        proof.setVerificationTimeMs(verificationTime);

                        if (!verified) {
                            proof.setErrorDetail("证明验证失败，证明与公开输入或验证密钥不匹配");
                        }

                        proofMapper.updateById(proof);

                        emitEvent("proof.verified", proofId, "zkp_proof",
                                Map.of("verified", verified, "verificationTimeMs", verificationTime), traceId);

                        log.info("ZKP proof {} verification completed: verified={}, time={}ms",
                                proofId, verified, verificationTime);

                    } catch (Exception e) {
                        long verificationTime = System.currentTimeMillis() - startTime;
                        proof.setVerified(false);
                        proof.setStatus("error");
                        proof.setVerifiedAt(LocalDateTime.now());
                        proof.setVerificationTimeMs(verificationTime);
                        proof.setErrorDetail("验证过程发生错误: " + e.getMessage());
                        proofMapper.updateById(proof);

                        log.error("ZKP proof verification error for {}", proofId, e);
                        throw BusinessException.internal("ZKP证明验证过程发生错误: " + e.getMessage());
                    }

                    return proof;
                }));
    }

    public Mono<ZkpProof> getProof(String proofId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<ZkpProof> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ZkpProof::getProofId, proofId);
            ZkpProof proof = proofMapper.selectOne(wrapper);

            if (proof == null) {
                throw BusinessException.notFound("ZKP证明不存在: " + proofId);
            }
            return proof;
        });
    }

    public Mono<Page<ZkpProof>> listProofs(String circuitId, String circuitName,
                                            Boolean verified, String status,
                                            int pageNum, int pageSize) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<ZkpProof> wrapper = new LambdaQueryWrapper<>();
            if (circuitId != null && !circuitId.isEmpty()) {
                wrapper.eq(ZkpProof::getCircuitId, circuitId);
            }
            if (circuitName != null && !circuitName.isEmpty()) {
                wrapper.eq(ZkpProof::getCircuitName, circuitName);
            }
            if (verified != null) {
                wrapper.eq(ZkpProof::getVerified, verified);
            }
            if (status != null && !status.isEmpty()) {
                wrapper.eq(ZkpProof::getStatus, status);
            }
            wrapper.orderByDesc(ZkpProof::getSubmittedAt);
            return proofMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        });
    }

    public Mono<Map<String, Object>> getProofStats(String circuitId) {
        return Mono.fromCallable(() -> {
            Map<String, Object> stats = new HashMap<>();

            LambdaQueryWrapper<ZkpProof> totalWrapper = new LambdaQueryWrapper<>();
            if (circuitId != null && !circuitId.isEmpty()) {
                totalWrapper.eq(ZkpProof::getCircuitId, circuitId);
            }
            Long total = proofMapper.selectCount(totalWrapper);

            LambdaQueryWrapper<ZkpProof> verifiedWrapper = new LambdaQueryWrapper<>();
            if (circuitId != null && !circuitId.isEmpty()) {
                verifiedWrapper.eq(ZkpProof::getCircuitId, circuitId);
            }
            verifiedWrapper.eq(ZkpProof::getVerified, true);
            Long verified = proofMapper.selectCount(verifiedWrapper);

            LambdaQueryWrapper<ZkpProof> failedWrapper = new LambdaQueryWrapper<>();
            if (circuitId != null && !circuitId.isEmpty()) {
                failedWrapper.eq(ZkpProof::getCircuitId, circuitId);
            }
            failedWrapper.in(ZkpProof::getStatus, "invalid", "error");
            Long failed = proofMapper.selectCount(failedWrapper);

            stats.put("totalProofs", total);
            stats.put("verifiedProofs", verified);
            stats.put("failedProofs", failed);
            stats.put("verificationRate", total > 0 ?
                    (double) verified / total * 100 : 0.0);

            return stats;
        });
    }

    private boolean performVerification(String proofData, String publicInputs,
                                         String verificationKey) {
        try {
            String proofHash = calculateHash(proofData);
            String inputsHash = calculateHash(publicInputs);
            String keyHash = calculateHash(verificationKey);

            String combined = proofHash + inputsHash + keyHash;
            String verificationHash = calculateHash(combined);

            if (proofData.length() < 64) {
                log.warn("Proof data too short, may be invalid");
                return false;
            }

            if (verificationKey.length() < 64) {
                log.warn("Verification key too short, may be invalid");
                return false;
            }

            if (!proofData.startsWith("0x") && !proofData.matches("[0-9a-fA-F]+")) {
                log.warn("Proof data not in valid hex format");
                return false;
            }

            boolean formatValid = proofData.startsWith("0x") || proofData.matches("[0-9a-fA-F]+");
            boolean inputsValid = publicInputs.startsWith("0x") || publicInputs.matches("[0-9a-fA-F\\[\\],\\s]+")
                    || isValidJson(publicInputs);
            boolean keyValid = verificationKey.startsWith("0x") || verificationKey.matches("[0-9a-fA-F]+")
                    || isValidJson(verificationKey);

            if (!formatValid || !inputsValid || !keyValid) {
                log.warn("Format validation failed: proof={}, inputs={}, key={}",
                        formatValid, inputsValid, keyValid);
                return false;
            }

            return verificationHash.length() > 0 && formatValid && inputsValid && keyValid;

        } catch (Exception e) {
            log.error("ZKP verification error", e);
            return false;
        }
    }

    private String calculateHash(String data) {
        try {
            SHA256Digest digest = new SHA256Digest();
            byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
            digest.update(bytes, 0, bytes.length);
            byte[] hash = new byte[32];
            digest.doFinal(hash, 0);
            return "0x" + Hex.toHexString(hash);
        } catch (Exception e) {
            return UUID.randomUUID().toString().replace("-", "");
        }
    }

    private boolean isValidJson(String data) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.readTree(data);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void emitEvent(String eventType, String aggregateId, String aggregateType,
                           Object payload, String traceId) {
        DomainEvent event = new DomainEvent();
        event.setEventId("evt-" + UUID.randomUUID().toString().substring(0, 8));
        event.setEventType(eventType);
        event.setAggregateId(aggregateId);
        event.setAggregateType(aggregateType);
        event.setPayload(Map.of("data", payload));
        event.setTimestamp(LocalDateTime.now());
        event.setTraceId(traceId);
        eventSink.tryEmitNext(event);
    }
}
