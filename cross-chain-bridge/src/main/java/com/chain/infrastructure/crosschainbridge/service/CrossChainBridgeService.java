package com.chain.infrastructure.crosschainbridge.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.chain.infrastructure.common.util.IdGenerator;
import com.chain.infrastructure.common.util.JsonUtils;
import com.chain.infrastructure.crosschainbridge.dto.CrossChainTransferRequest;
import com.chain.infrastructure.crosschainbridge.dto.CrossChainTransferResult;
import com.chain.infrastructure.crosschainbridge.dto.MessageVerificationRequest;
import com.chain.infrastructure.persistence.entity.CrossChainTransfer;
import com.chain.infrastructure.persistence.mapper.CrossChainTransferMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class CrossChainBridgeService {

    private final CrossChainTransferMapper transferMapper;

    public Mono<CrossChainTransferResult> initiateTransfer(CrossChainTransferRequest request) {
        return Mono.fromCallable(() -> {
            String transferId = IdGenerator.generateId("xfer");

            CrossChainTransfer transfer = new CrossChainTransfer();
            transfer.setTransferId(transferId);
            transfer.setSourceChain(request.getSourceChain());
            transfer.setTargetChain(request.getTargetChain());
            transfer.setSourceAddress(request.getSourceAddress());
            transfer.setTargetAddress(request.getTargetAddress());
            transfer.setTokenAddress(request.getTokenAddress());
            transfer.setAmount(request.getAmount());
            transfer.setFee(request.getFee());
            transfer.setSourceTxHash(request.getSourceTxHash());
            transfer.setStatus("LOCKING");
            transfer.setExpiresAt(LocalDateTime.now().plusDays(1));
            transferMapper.insert(transfer);

            CrossChainTransferResult result = new CrossChainTransferResult();
            result.setTransferId(transferId);
            result.setSourceChain(request.getSourceChain());
            result.setTargetChain(request.getTargetChain());
            result.setSourceAddress(request.getSourceAddress());
            result.setTargetAddress(request.getTargetAddress());
            result.setTokenAddress(request.getTokenAddress());
            result.setAmount(request.getAmount());
            result.setFee(request.getFee());
            result.setSourceTxHash(request.getSourceTxHash());
            result.setStatus("LOCKING");
            result.setCreatedAt(LocalDateTime.now());

            log.info("Cross-chain transfer initiated: transferId={}, {} -> {}, amount={}",
                    transferId, request.getSourceChain(), request.getTargetChain(), request.getAmount());

            return result;
        });
    }

    public Mono<CrossChainTransfer> lockAssets(String transferId, String sourceTxHash) {
        return Mono.fromCallable(() -> {
            CrossChainTransfer transfer = transferMapper.selectById(transferId);
            if (transfer == null) {
                throw new IllegalArgumentException("Transfer not found: " + transferId);
            }

            transfer.setSourceTxHash(sourceTxHash);
            transfer.setStatus("LOCKED");
            transfer.setMessageProof(generateMessageProof(transfer));
            transferMapper.updateById(transfer);

            log.info("Assets locked: transferId={}, sourceTxHash={}", transferId, sourceTxHash);
            return transfer;
        });
    }

    public Mono<Boolean> verifyMessage(MessageVerificationRequest request) {
        return Mono.fromCallable(() -> {
            CrossChainTransfer transfer = transferMapper.selectById(request.getTransferId());
            if (transfer == null) {
                throw new IllegalArgumentException("Transfer not found: " + request.getTransferId());
            }

            boolean isValid = validateProof(request);

            if (isValid) {
                transfer.setStatus("VERIFIED");
                transfer.setMessageProof(request.getMessageProof());
                transferMapper.updateById(transfer);
                log.info("Message verified: transferId={}", request.getTransferId());
            } else {
                log.warn("Message verification failed: transferId={}", request.getTransferId());
            }

            return isValid;
        });
    }

    public Mono<CrossChainTransfer> mintAssets(String transferId) {
        return Mono.fromCallable(() -> {
            CrossChainTransfer transfer = transferMapper.selectById(transferId);
            if (transfer == null) {
                throw new IllegalArgumentException("Transfer not found: " + transferId);
            }

            if (!"VERIFIED".equals(transfer.getStatus())) {
                throw new IllegalStateException("Transfer not verified: " + transfer.getStatus());
            }

            String targetTxHash = IdGenerator.generateHash(transferId + System.currentTimeMillis());
            transfer.setTargetTxHash(targetTxHash);
            transfer.setStatus("MINTED");
            transfer.setConfirmedAt(LocalDateTime.now());
            transferMapper.updateById(transfer);

            log.info("Assets minted: transferId={}, targetTxHash={}", transferId, targetTxHash);
            return transfer;
        });
    }

    public Mono<CrossChainTransfer> executeTransfer(String transferId) {
        return lockAssets(transferId, IdGenerator.generateHash("lock_" + transferId))
                .flatMap(t -> verifyMessage(buildVerificationRequest(t)))
                .flatMap(verified -> {
                    if (!verified) {
                        return Mono.error(new IllegalStateException("Message verification failed"));
                    }
                    return mintAssets(transferId);
                });
    }

    public Mono<CrossChainTransfer> getTransfer(String transferId) {
        return Mono.justOrEmpty(transferMapper.selectById(transferId));
    }

    public Mono<CrossChainTransfer> getTransferBySourceTx(String sourceChain, String sourceTxHash) {
        return Mono.fromCallable(() -> {
            QueryWrapper<CrossChainTransfer> wrapper = new QueryWrapper<>();
            wrapper.eq("source_chain", sourceChain)
                    .eq("source_tx_hash", sourceTxHash);
            return transferMapper.selectOne(wrapper);
        });
    }

    public Mono<CrossChainTransfer> getTransferByTargetTx(String targetChain, String targetTxHash) {
        return Mono.fromCallable(() -> {
            QueryWrapper<CrossChainTransfer> wrapper = new QueryWrapper<>();
            wrapper.eq("target_chain", targetChain)
                    .eq("target_tx_hash", targetTxHash);
            return transferMapper.selectOne(wrapper);
        });
    }

    private String generateMessageProof(CrossChainTransfer transfer) {
        Map<String, Object> proof = new HashMap<>();
        proof.put("transferId", transfer.getTransferId());
        proof.put("sourceChain", transfer.getSourceChain());
        proof.put("targetChain", transfer.getTargetChain());
        proof.put("amount", transfer.getAmount());
        proof.put("sourceTxHash", transfer.getSourceTxHash());
        proof.put("timestamp", System.currentTimeMillis());
        return IdGenerator.generateHash(JsonUtils.toJson(proof));
    }

    private boolean validateProof(MessageVerificationRequest request) {
        return request.getMessageProof() != null && request.getMessageProof().length() >= 32;
    }

    private MessageVerificationRequest buildVerificationRequest(CrossChainTransfer transfer) {
        MessageVerificationRequest request = new MessageVerificationRequest();
        request.setTransferId(transfer.getTransferId());
        request.setSourceChain(transfer.getSourceChain());
        request.setTargetChain(transfer.getTargetChain());
        request.setMessageProof(transfer.getMessageProof());
        return request;
    }
}
