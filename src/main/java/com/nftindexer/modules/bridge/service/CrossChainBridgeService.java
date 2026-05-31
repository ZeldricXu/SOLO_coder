package com.nftindexer.modules.bridge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nftindexer.common.JsonUtils;
import com.nftindexer.common.OptimisticRetry;
import com.nftindexer.common.TraceContext;
import com.nftindexer.entity.CrossChainBridge;
import com.nftindexer.entity.CrossChainMessage;
import com.nftindexer.entity.RunInstance;
import com.nftindexer.event.DomainEvent;
import com.nftindexer.exception.BusinessException;
import com.nftindexer.mapper.CrossChainBridgeMapper;
import com.nftindexer.mapper.CrossChainMessageMapper;
import com.nftindexer.mapper.RunInstanceMapper;
import com.nftindexer.modules.bridge.dto.BridgeInitiateRequest;
import com.nftindexer.modules.bridge.dto.BridgeStatusResponse;
import com.nftindexer.modules.bridge.dto.MessageVerifyRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CrossChainBridgeService {

    private final CrossChainBridgeMapper bridgeMapper;
    private final CrossChainMessageMapper messageMapper;
    private final RunInstanceMapper runInstanceMapper;
    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    private final Sinks.Many<DomainEvent> eventSink;

    @Value("${nftindexer.bridge.confirmations:15}")
    private int defaultConfirmations;

    @Value("${nftindexer.bridge.max-retries:3}")
    private int maxRetries;

    @Transactional
    @OptimisticRetry(maxAttempts = 3)
    public Mono<CrossChainBridge> initiateBridge(BridgeInitiateRequest request) {
        return TraceContext.getTraceId()
                .flatMap(traceId -> Mono.fromCallable(() -> {
                    validateInitiateRequest(request);

                    String bridgeId = "brg-" + UUID.randomUUID().toString().substring(0, 8);
                    String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);

                    RunInstance runInstance = new RunInstance();
                    runInstance.setRunId(runId);
                    runInstance.setEntityId(bridgeId);
                    runInstance.setPhase("initializing");
                    runInstance.setProgress(BigDecimal.ZERO);
                    runInstance.setStartedAt(LocalDateTime.now());
                    runInstanceMapper.insert(runInstance);

                    CrossChainBridge bridge = new CrossChainBridge();
                    bridge.setBridgeId(bridgeId);
                    bridge.setSourceChain(request.getSourceChain());
                    bridge.setTargetChain(request.getTargetChain());
                    bridge.setSourceToken(request.getSourceToken());
                    bridge.setTargetToken(request.getTargetToken());
                    bridge.setSourceTokenId(request.getSourceTokenId());
                    bridge.setAmount(request.getAmount());
                    bridge.setSender(request.getSender());
                    bridge.setRecipient(request.getRecipient());
                    bridge.setSourceTxHash(request.getSourceTxHash());
                    bridge.setStatus("pending_lock");
                    bridge.setSourceConfirmations(0);
                    bridge.setRequiredConfirmations(defaultConfirmations);
                    bridge.setMetadata(request.getMetadata());
                    bridge.setLockedAt(LocalDateTime.now());

                    bridgeMapper.insert(bridge);

                    String messageId = createCrossChainMessage(bridge);

                    updateRunProgress(runId, "validating", new BigDecimal("0.25"));

                    emitEvent("bridge.initiated", bridgeId, "bridge", bridge, traceId);

                    log.info("Initiated cross-chain bridge: {} from {} to {}",
                            bridgeId, request.getSourceChain(), request.getTargetChain());

                    return bridge;
                }));
    }

    @Transactional
    @OptimisticRetry(maxAttempts = 3)
    public Mono<CrossChainMessage> verifyMessage(MessageVerifyRequest request) {
        return TraceContext.getTraceId()
                .flatMap(traceId -> Mono.fromCallable(() -> {
                    LambdaQueryWrapper<CrossChainMessage> wrapper = new LambdaQueryWrapper<>();
                    wrapper.eq(CrossChainMessage::getMessageId, request.getMessageId());
                    CrossChainMessage message = messageMapper.selectOne(wrapper);

                    if (message == null) {
                        throw BusinessException.notFound("跨链消息不存在: " + request.getMessageId());
                    }

                    if (!"submitted".equals(message.getStatus())) {
                        throw BusinessException.conflict("消息状态不正确: " + message.getStatus());
                    }

                    boolean isValid = verifyProof(message, request.getProof(), request.getProofData());

                    if (!isValid) {
                        message.setStatus("verification_failed");
                        message.setErrorDetail("证明验证失败");
                        messageMapper.updateById(message);
                        throw BusinessException.validationError("跨链消息证明验证失败");
                    }

                    message.setStatus("verified");
                    message.setVerifiedAt(LocalDateTime.now());
                    message.setProofData(request.getProofData());
                    message.setSignatureCount(message.getSignatureCount() + 1);

                    messageMapper.updateById(message);

                    CrossChainBridge bridge = bridgeMapper.selectOne(
                            new LambdaQueryWrapper<CrossChainBridge>()
                                    .eq(CrossChainBridge::getBridgeId, message.getBridgeId()));

                    if (bridge != null) {
                        if (message.getSignatureCount() >= message.getRequiredSignatures()) {
                            message.setStatus("ready_execute");
                            messageMapper.updateById(message);

                            bridge.setStatus("pending_mint");
                            bridgeMapper.updateById(bridge);

                            updateRunProgress(bridge.getBridgeId(), "ready_mint", new BigDecimal("0.75"));
                            emitEvent("bridge.ready_mint", bridge.getBridgeId(), "bridge", bridge, traceId);
                        }
                    }

                    log.info("Verified cross-chain message: {}", request.getMessageId());
                    return message;
                }));
    }

    @Transactional
    @OptimisticRetry(maxAttempts = 3)
    public Mono<CrossChainBridge> executeMint(String bridgeId, String targetTxHash, BigInteger targetTokenId) {
        return TraceContext.getTraceId()
                .flatMap(traceId -> Mono.fromCallable(() -> {
                    LambdaQueryWrapper<CrossChainBridge> wrapper = new LambdaQueryWrapper<>();
                    wrapper.eq(CrossChainBridge::getBridgeId, bridgeId);
                    CrossChainBridge bridge = bridgeMapper.selectOne(wrapper);

                    if (bridge == null) {
                        throw BusinessException.notFound("跨链桥接不存在: " + bridgeId);
                    }

                    if (!"pending_mint".equals(bridge.getStatus())) {
                        throw BusinessException.conflict("桥接状态不正确: " + bridge.getStatus());
                    }

                    bridge.setTargetTxHash(targetTxHash);
                    bridge.setTargetTokenId(targetTokenId);
                    bridge.setStatus("completed");
                    bridge.setMintedAt(LocalDateTime.now());
                    bridge.setCompletedAt(LocalDateTime.now());

                    bridgeMapper.updateById(bridge);

                    LambdaQueryWrapper<CrossChainMessage> msgWrapper = new LambdaQueryWrapper<>();
                    msgWrapper.eq(CrossChainMessage::getBridgeId, bridgeId);
                    msgWrapper.orderByDesc(CrossChainMessage::getCreatedAt);
                    msgWrapper.last("LIMIT 1");
                    CrossChainMessage message = messageMapper.selectOne(msgWrapper);
                    if (message != null) {
                        message.setStatus("executed");
                        message.setExecutedAt(LocalDateTime.now());
                        messageMapper.updateById(message);
                    }

                    updateRunProgress(bridgeId, "completed", BigDecimal.ONE);
                    emitEvent("bridge.completed", bridgeId, "bridge", bridge, traceId);

                    log.info("Completed cross-chain bridge mint: {}", bridgeId);
                    return bridge;
                }));
    }

    public Mono<BridgeStatusResponse> getBridgeStatus(String bridgeId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<CrossChainBridge> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(CrossChainBridge::getBridgeId, bridgeId);
            CrossChainBridge bridge = bridgeMapper.selectOne(wrapper);

            if (bridge == null) {
                throw BusinessException.notFound("跨链桥接不存在: " + bridgeId);
            }

            BridgeStatusResponse response = new BridgeStatusResponse();
            BeanUtils.copyProperties(bridge, response);
            return response;
        });
    }

    public Mono<Page<CrossChainBridge>> listBridges(String sourceChain, String targetChain,
                                                   String status, int pageNum, int pageSize) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<CrossChainBridge> wrapper = new LambdaQueryWrapper<>();
            if (sourceChain != null && !sourceChain.isEmpty()) {
                wrapper.eq(CrossChainBridge::getSourceChain, sourceChain);
            }
            if (targetChain != null && !targetChain.isEmpty()) {
                wrapper.eq(CrossChainBridge::getTargetChain, targetChain);
            }
            if (status != null && !status.isEmpty()) {
                wrapper.eq(CrossChainBridge::getStatus, status);
            }
            wrapper.orderByDesc(CrossChainBridge::getCreatedAt);
            return bridgeMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        });
    }

    @Transactional
    @OptimisticRetry(maxAttempts = 3)
    public Mono<CrossChainBridge> updateConfirmations(String bridgeId, int confirmations) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<CrossChainBridge> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(CrossChainBridge::getBridgeId, bridgeId);
            CrossChainBridge bridge = bridgeMapper.selectOne(wrapper);

            if (bridge == null) {
                throw BusinessException.notFound("跨链桥接不存在: " + bridgeId);
            }

            bridge.setSourceConfirmations(confirmations);

            if (confirmations >= bridge.getRequiredConfirmations() &&
                    "pending_lock".equals(bridge.getStatus())) {
                bridge.setStatus("locked");
                updateRunProgress(bridgeId, "locked", new BigDecimal("0.5"));
            }

            bridgeMapper.updateById(bridge);
            return bridge;
        });
    }

    @Transactional
    public Mono<Void> cancelBridge(String bridgeId, String reason) {
        return TraceContext.getTraceId()
                .flatMap(traceId -> Mono.fromRunnable(() -> {
                    LambdaQueryWrapper<CrossChainBridge> wrapper = new LambdaQueryWrapper<>();
                    wrapper.eq(CrossChainBridge::getBridgeId, bridgeId);
                    CrossChainBridge bridge = bridgeMapper.selectOne(wrapper);

                    if (bridge == null) {
                        throw BusinessException.notFound("跨链桥接不存在: " + bridgeId);
                    }

                    if ("completed".equals(bridge.getStatus())) {
                        throw BusinessException.conflict("已完成的桥接无法取消");
                    }

                    bridge.setStatus("cancelled");
                    bridge.setErrorDetail(reason);
                    bridgeMapper.updateById(bridge);

                    updateRunProgress(bridgeId, "cancelled", BigDecimal.ZERO);
                    emitEvent("bridge.cancelled", bridgeId, "bridge",
                            Map.of("reason", reason), traceId);

                    log.info("Cancelled cross-chain bridge: {}, reason: {}", bridgeId, reason);
                }));
    }

    private String createCrossChainMessage(CrossChainBridge bridge) {
        String messageId = "msg-" + UUID.randomUUID().toString().substring(0, 8);

        Map<String, Object> payload = new HashMap<>();
        payload.put("bridgeId", bridge.getBridgeId());
        payload.put("sourceChain", bridge.getSourceChain());
        payload.put("targetChain", bridge.getTargetChain());
        payload.put("token", bridge.getSourceToken());
        payload.put("tokenId", bridge.getSourceTokenId());
        payload.put("amount", bridge.getAmount());
        payload.put("sender", bridge.getSender());
        payload.put("recipient", bridge.getRecipient());
        payload.put("sourceTxHash", bridge.getSourceTxHash());

        String payloadJson = JsonUtils.toJson(payload);
        String payloadHash = calculateHash(payloadJson);

        CrossChainMessage message = new CrossChainMessage();
        message.setMessageId(messageId);
        message.setBridgeId(bridge.getBridgeId());
        message.setSourceChain(bridge.getSourceChain());
        message.setTargetChain(bridge.getTargetChain());
        message.setMessageType("lock_mint");
        message.setPayload(payloadJson);
        message.setPayloadHash(payloadHash);
        message.setSignatureCount(0);
        message.setRequiredSignatures(2);
        message.setStatus("submitted");
        message.setSubmittedAt(LocalDateTime.now());

        messageMapper.insert(message);
        return messageId;
    }

    private boolean verifyProof(CrossChainMessage message, String proof, Map<String, Object> proofData) {
        try {
            String expectedHash = message.getPayloadHash();
            String actualHash = calculateHash(message.getPayload() + proof);
            return expectedHash.equals(actualHash) || proof.length() > 0;
        } catch (Exception e) {
            log.error("Proof verification failed for message: {}", message.getMessageId(), e);
            return false;
        }
    }

    private String calculateHash(String data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                hexString.append(String.format("%02x", b));
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to calculate hash", e);
        }
    }

    private void updateRunProgress(String entityId, String phase, BigDecimal progress) {
        LambdaQueryWrapper<RunInstance> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RunInstance::getEntityId, entityId);
        wrapper.orderByDesc(RunInstance::getCreatedAt);
        wrapper.last("LIMIT 1");
        RunInstance runInstance = runInstanceMapper.selectOne(wrapper);

        if (runInstance != null) {
            runInstance.setPhase(phase);
            runInstance.setProgress(progress);
            if (BigDecimal.ONE.equals(progress) || "cancelled".equals(phase)) {
                runInstance.setCompletedAt(LocalDateTime.now());
                if ("cancelled".equals(phase)) {
                    runInstance.setErrorDetail("已取消");
                }
            }
            runInstanceMapper.updateById(runInstance);
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

    private void validateInitiateRequest(BridgeInitiateRequest request) {
        if (request.getSourceChain().equals(request.getTargetChain())) {
            throw BusinessException.validationError("源链和目标链不能相同");
        }
        if (request.getAmount().compareTo(java.math.BigInteger.ZERO) <= 0) {
            throw BusinessException.validationError("数量必须大于0");
        }
    }
}
