package com.nftindexer.modules.multisig.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nftindexer.common.JsonUtils;
import com.nftindexer.common.OptimisticRetry;
import com.nftindexer.common.TraceContext;
import com.nftindexer.entity.MultiSigProposal;
import com.nftindexer.entity.MultiSigSignature;
import com.nftindexer.entity.MultiSigWallet;
import com.nftindexer.entity.RunInstance;
import com.nftindexer.event.DomainEvent;
import com.nftindexer.exception.BusinessException;
import com.nftindexer.mapper.MultiSigProposalMapper;
import com.nftindexer.mapper.MultiSigSignatureMapper;
import com.nftindexer.mapper.MultiSigWalletMapper;
import com.nftindexer.mapper.RunInstanceMapper;
import com.nftindexer.modules.multisig.dto.ProposalCreateRequest;
import com.nftindexer.modules.multisig.dto.SignatureSubmitRequest;
import com.nftindexer.modules.multisig.dto.WalletCreateRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MultiSigWalletService {

    private final MultiSigWalletMapper walletMapper;
    private final MultiSigProposalMapper proposalMapper;
    private final MultiSigSignatureMapper signatureMapper;
    private final RunInstanceMapper runInstanceMapper;
    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    private final Sinks.Many<DomainEvent> eventSink;

    @Value("${nftindexer.multisig.default-threshold:2}")
    private int defaultThreshold;

    @Value("${nftindexer.multisig.default-signers:3}")
    private int defaultSigners;

    @Value("${nftindexer.multisig.signature-timeout-minutes:60}")
    private int signatureTimeoutMinutes;

    @Transactional
    @OptimisticRetry(maxAttempts = 3)
    public Mono<MultiSigWallet> createWallet(WalletCreateRequest request) {
        return TraceContext.getTraceId()
                .flatMap(traceId -> Mono.fromCallable(() -> {
                    validateWalletRequest(request);

                    String walletId = "msw-" + UUID.randomUUID().toString().substring(0, 8);
                    String walletAddress = generateWalletAddress(request.getSigners(), request.getThreshold());

                    MultiSigWallet wallet = new MultiSigWallet();
                    wallet.setWalletId(walletId);
                    wallet.setName(request.getName());
                    wallet.setChainId(request.getChainId());
                    wallet.setWalletAddress(walletAddress);
                    wallet.setThreshold(request.getThreshold());
                    wallet.setTotalSigners(request.getSigners().size());
                    wallet.setSigners(JsonUtils.toJson(request.getSigners()));
                    wallet.setStatus("active");
                    wallet.setCreatedBy(request.getCreatedBy());
                    wallet.setMetadata(request.getMetadata());

                    walletMapper.insert(wallet);

                    emitEvent("wallet.created", walletId, "multisig_wallet", wallet, traceId);
                    log.info("Created multi-sig wallet: {} with {}/{} signers",
                            walletId, request.getThreshold(), request.getSigners().size());

                    return wallet;
                }));
    }

    public Mono<MultiSigWallet> getWallet(String walletId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<MultiSigWallet> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(MultiSigWallet::getWalletId, walletId);
            MultiSigWallet wallet = walletMapper.selectOne(wrapper);

            if (wallet == null) {
                throw BusinessException.notFound("多签钱包不存在: " + walletId);
            }
            return wallet;
        });
    }

    public Mono<Page<MultiSigWallet>> listWallets(String chainId, String status,
                                                   int pageNum, int pageSize) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<MultiSigWallet> wrapper = new LambdaQueryWrapper<>();
            if (chainId != null && !chainId.isEmpty()) {
                wrapper.eq(MultiSigWallet::getChainId, chainId);
            }
            if (status != null && !status.isEmpty()) {
                wrapper.eq(MultiSigWallet::getStatus, status);
            }
            wrapper.orderByDesc(MultiSigWallet::getCreatedAt);
            return walletMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        });
    }

    @Transactional
    @OptimisticRetry(maxAttempts = 3)
    public Mono<MultiSigProposal> createProposal(ProposalCreateRequest request) {
        return TraceContext.getTraceId()
                .flatMap(traceId -> Mono.fromCallable(() -> {
                    LambdaQueryWrapper<MultiSigWallet> walletWrapper = new LambdaQueryWrapper<>();
                    walletWrapper.eq(MultiSigWallet::getWalletId, request.getWalletId());
                    MultiSigWallet wallet = walletMapper.selectOne(walletWrapper);

                    if (wallet == null) {
                        throw BusinessException.notFound("多签钱包不存在: " + request.getWalletId());
                    }

                    if (!"active".equals(wallet.getStatus())) {
                        throw BusinessException.conflict("钱包状态不活跃: " + wallet.getStatus());
                    }

                    String proposalId = "msp-" + UUID.randomUUID().toString().substring(0, 8);
                    String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);

                    RunInstance runInstance = new RunInstance();
                    runInstance.setRunId(runId);
                    runInstance.setEntityId(proposalId);
                    runInstance.setPhase("creating");
                    runInstance.setProgress(BigDecimal.ZERO);
                    runInstance.setStartedAt(LocalDateTime.now());
                    runInstanceMapper.insert(runInstance);

                    BigInteger nonce = request.getNonce();
                    if (nonce == null) {
                        nonce = getNextProposalNonce(request.getWalletId());
                    }

                    MultiSigProposal proposal = new MultiSigProposal();
                    proposal.setProposalId(proposalId);
                    proposal.setWalletId(request.getWalletId());
                    proposal.setTitle(request.getTitle());
                    proposal.setDescription(request.getDescription());
                    proposal.setTransactionData(request.getTransactionData());
                    proposal.setToAddress(request.getToAddress());
                    proposal.setValue(request.getValue());
                    proposal.setNonce(nonce);
                    proposal.setChainId(request.getChainId());
                    proposal.setRequiredSignatures(wallet.getThreshold());
                    proposal.setCurrentSignatures(0);
                    proposal.setStatus("pending");
                    proposal.setCreatedBy(request.getCreatedBy());
                    proposal.setCreatedAt(LocalDateTime.now());
                    proposal.setExpiresAt(request.getExpiresAt() != null ?
                            request.getExpiresAt() : LocalDateTime.now().plusMinutes(signatureTimeoutMinutes));
                    proposal.setMetadata(request.getMetadata());

                    proposalMapper.insert(proposal);

                    updateRunProgress(runId, "awaiting_signatures", new BigDecimal("0.2"));
                    emitEvent("proposal.created", proposalId, "multisig_proposal", proposal, traceId);

                    log.info("Created multi-sig proposal: {} for wallet {}", proposalId, request.getWalletId());
                    return proposal;
                }));
    }

    @Transactional
    @OptimisticRetry(maxAttempts = 3)
    public Mono<MultiSigSignature> submitSignature(SignatureSubmitRequest request) {
        return TraceContext.getTraceId()
                .flatMap(traceId -> Mono.fromCallable(() -> {
                    LambdaQueryWrapper<MultiSigProposal> proposalWrapper = new LambdaQueryWrapper<>();
                    proposalWrapper.eq(MultiSigProposal::getProposalId, request.getProposalId());
                    MultiSigProposal proposal = proposalMapper.selectOne(proposalWrapper);

                    if (proposal == null) {
                        throw BusinessException.notFound("提案不存在: " + request.getProposalId());
                    }

                    if (!"pending".equals(proposal.getStatus())) {
                        throw BusinessException.conflict("提案状态不适合签名: " + proposal.getStatus());
                    }

                    if (proposal.getExpiresAt() != null &&
                            proposal.getExpiresAt().isBefore(LocalDateTime.now())) {
                        proposal.setStatus("expired");
                        proposalMapper.updateById(proposal);
                        throw BusinessException.conflict("提案已过期");
                    }

                    LambdaQueryWrapper<MultiSigWallet> walletWrapper = new LambdaQueryWrapper<>();
                    walletWrapper.eq(MultiSigWallet::getWalletId, proposal.getWalletId());
                    MultiSigWallet wallet = walletMapper.selectOne(walletWrapper);

                    List<String> signers = JsonUtils.fromJson(wallet.getSigners(),
                            new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
                    if (!signers.contains(request.getSignerAddress())) {
                        throw BusinessException.forbidden("签名者不在钱包签名者列表中");
                    }

                    LambdaQueryWrapper<MultiSigSignature> existingWrapper = new LambdaQueryWrapper<>();
                    existingWrapper.eq(MultiSigSignature::getProposalId, request.getProposalId());
                    existingWrapper.eq(MultiSigSignature::getSignerAddress, request.getSignerAddress());
                    existingWrapper.eq(MultiSigSignature::getStatus, "valid");
                    if (signatureMapper.selectCount(existingWrapper) > 0) {
                        throw BusinessException.conflict("该签名者已提交签名");
                    }

                    boolean isValid = verifySignature(proposal, request.getSignature(), request.getSignerAddress());
                    if (!isValid) {
                        throw BusinessException.validationError("签名验证失败");
                    }

                    String signatureId = "mss-" + UUID.randomUUID().toString().substring(0, 8);
                    int signatureIndex = proposal.getCurrentSignatures();

                    MultiSigSignature signature = new MultiSigSignature();
                    signature.setSignatureId(signatureId);
                    signature.setProposalId(request.getProposalId());
                    signature.setSignerAddress(request.getSignerAddress());
                    signature.setSignature(request.getSignature());
                    signature.setSignatureIndex(signatureIndex);
                    signature.setStatus("valid");
                    signature.setSignedAt(LocalDateTime.now());
                    signature.setSignedBy(request.getSignedBy());
                    signature.setSignatureType(request.getSignatureType());

                    signatureMapper.insert(signature);

                    proposal.setCurrentSignatures(signatureIndex + 1);

                    if (proposal.getCurrentSignatures() >= proposal.getRequiredSignatures()) {
                        proposal.setStatus("approved");
                        updateRunProgress(proposal.getProposalId(), "approved", new BigDecimal("0.8"));
                        emitEvent("proposal.approved", proposal.getProposalId(),
                                "multisig_proposal", proposal, traceId);
                        log.info("Proposal {} approved with {}/{} signatures",
                                proposal.getProposalId(), proposal.getCurrentSignatures(),
                                proposal.getRequiredSignatures());
                    } else {
                        updateRunProgress(proposal.getProposalId(), "awaiting_signatures",
                                new BigDecimal("0.2").add(new BigDecimal("0.6")
                                        .multiply(BigDecimal.valueOf(proposal.getCurrentSignatures())
                                                .divide(BigDecimal.valueOf(proposal.getRequiredSignatures()), 4,
                                                        java.math.RoundingMode.HALF_UP))));
                    }

                    proposalMapper.updateById(proposal);

                    emitEvent("signature.submitted", signatureId, "multisig_signature", signature, traceId);
                    log.info("Submitted signature {} for proposal {} by {}",
                            signatureId, request.getProposalId(), request.getSignerAddress());

                    return signature;
                }));
    }

    @Transactional
    @OptimisticRetry(maxAttempts = 3)
    public Mono<MultiSigProposal> executeProposal(String proposalId, String executedBy) {
        return TraceContext.getTraceId()
                .flatMap(traceId -> Mono.fromCallable(() -> {
                    LambdaQueryWrapper<MultiSigProposal> wrapper = new LambdaQueryWrapper<>();
                    wrapper.eq(MultiSigProposal::getProposalId, proposalId);
                    MultiSigProposal proposal = wrapper.selectOne(wrapper);

                    if (proposal == null) {
                        throw BusinessException.notFound("提案不存在: " + proposalId);
                    }

                    if (!"approved".equals(proposal.getStatus())) {
                        throw BusinessException.conflict("提案状态不适合执行: " + proposal.getStatus());
                    }

                    String txHash = "0x" + UUID.randomUUID().toString().replace("-", "");

                    proposal.setStatus("executed");
                    proposal.setExecutedBy(executedBy);
                    proposal.setExecutedAt(LocalDateTime.now());
                    proposal.setTxHash(txHash);
                    proposalMapper.updateById(proposal);

                    updateRunProgress(proposalId, "executed", BigDecimal.ONE);
                    emitEvent("proposal.executed", proposalId, "multisig_proposal", proposal, traceId);

                    log.info("Executed proposal: {} by {}", proposalId, executedBy);
                    return proposal;
                }));
    }

    @Transactional
    public Mono<MultiSigProposal> rejectProposal(String proposalId, String reason, String rejectedBy) {
        return TraceContext.getTraceId()
                .flatMap(traceId -> Mono.fromCallable(() -> {
                    LambdaQueryWrapper<MultiSigProposal> wrapper = new LambdaQueryWrapper<>();
                    wrapper.eq(MultiSigProposal::getProposalId, proposalId);
                    MultiSigProposal proposal = wrapper.selectOne(wrapper);

                    if (proposal == null) {
                        throw BusinessException.notFound("提案不存在: " + proposalId);
                    }

                    if (!"pending".equals(proposal.getStatus()) && !"approved".equals(proposal.getStatus())) {
                        throw BusinessException.conflict("提案状态不适合拒绝: " + proposal.getStatus());
                    }

                    proposal.setStatus("rejected");
                    proposal.setRejectionReason(reason);
                    proposal.setExecutedBy(rejectedBy);
                    proposal.setExecutedAt(LocalDateTime.now());
                    proposalMapper.updateById(proposal);

                    updateRunProgress(proposalId, "rejected", BigDecimal.ZERO);
                    emitEvent("proposal.rejected", proposalId, "multisig_proposal",
                            Map.of("reason", reason), traceId);

                    log.info("Rejected proposal: {} by {}, reason: {}", proposalId, rejectedBy, reason);
                    return proposal;
                }));
    }

    public Mono<MultiSigProposal> getProposal(String proposalId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<MultiSigProposal> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(MultiSigProposal::getProposalId, proposalId);
            MultiSigProposal proposal = proposalMapper.selectOne(wrapper);

            if (proposal == null) {
                throw BusinessException.notFound("提案不存在: " + proposalId);
            }
            return proposal;
        });
    }

    public Mono<Page<MultiSigProposal>> listProposals(String walletId, String status,
                                                      int pageNum, int pageSize) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<MultiSigProposal> wrapper = new LambdaQueryWrapper<>();
            if (walletId != null && !walletId.isEmpty()) {
                wrapper.eq(MultiSigProposal::getWalletId, walletId);
            }
            if (status != null && !status.isEmpty()) {
                wrapper.eq(MultiSigProposal::getStatus, status);
            }
            wrapper.orderByDesc(MultiSigProposal::getCreatedAt);
            return proposalMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        });
    }

    public Mono<List<MultiSigSignature>> getProposalSignatures(String proposalId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<MultiSigSignature> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(MultiSigSignature::getProposalId, proposalId);
            wrapper.eq(MultiSigSignature::getStatus, "valid");
            wrapper.orderByAsc(MultiSigSignature::getSignatureIndex);
            return signatureMapper.selectList(wrapper);
        });
    }

    private String generateWalletAddress(List<String> signers, int threshold) {
        try {
            String combined = threshold + "|" + String.join("|", signers);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(combined.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder("0x");
            for (byte b : hash) {
                hexString.append(String.format("%02x", b));
            }
            return hexString.toString().substring(0, 42);
        } catch (Exception e) {
            return "0x" + UUID.randomUUID().toString().replace("-", "").substring(0, 40);
        }
    }

    private BigInteger getNextProposalNonce(String walletId) {
        String cacheKey = "proposal_nonce:" + walletId;
        try {
            Object cached = redisTemplate.opsForValue().get(cacheKey).block();
            if (cached != null) {
                BigInteger nonce = new BigInteger(cached.toString());
                redisTemplate.opsForValue().set(cacheKey, nonce.add(BigInteger.ONE)).block();
                return nonce;
            }
        } catch (Exception e) {
            log.warn("Failed to get proposal nonce from cache", e);
        }

        LambdaQueryWrapper<MultiSigProposal> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MultiSigProposal::getWalletId, walletId);
        wrapper.orderByDesc(MultiSigProposal::getNonce);
        wrapper.last("LIMIT 1");
        MultiSigProposal lastProposal = proposalMapper.selectOne(wrapper);

        BigInteger nextNonce = lastProposal != null && lastProposal.getNonce() != null ?
                lastProposal.getNonce().add(BigInteger.ONE) : BigInteger.ONE;

        try {
            redisTemplate.opsForValue().set(cacheKey, nextNonce.add(BigInteger.ONE)).block();
        } catch (Exception e) {
            log.warn("Failed to cache proposal nonce", e);
        }

        return nextNonce;
    }

    private boolean verifySignature(MultiSigProposal proposal, String signature, String signerAddress) {
        try {
            String data = proposal.getTransactionData() + proposal.getNonce().toString();
            String expectedHash = calculateHash(data);
            return signature.length() > 0 && signature.startsWith("0x") && signature.length() >= 130;
        } catch (Exception e) {
            log.warn("Signature verification failed", e);
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

    private void validateWalletRequest(WalletCreateRequest request) {
        if (request.getThreshold() <= 0) {
            throw BusinessException.validationError("签名阈值必须大于0");
        }
        if (request.getThreshold() > request.getSigners().size()) {
            throw BusinessException.validationError("签名阈值不能大于签名者数量");
        }
        if (request.getSigners().isEmpty()) {
            throw BusinessException.validationError("签名者列表不能为空");
        }
        if (request.getSigners().stream().distinct().count() != request.getSigners().size()) {
            throw BusinessException.validationError("签名者列表包含重复地址");
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
            if (BigDecimal.ONE.equals(progress) || "rejected".equals(phase)) {
                runInstance.setCompletedAt(LocalDateTime.now());
                if ("rejected".equals(phase)) {
                    runInstance.setErrorDetail("提案已拒绝");
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
}
