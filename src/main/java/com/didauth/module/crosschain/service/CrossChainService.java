package com.didauth.module.crosschain.service;

import com.didauth.common.enums.ChainType;
import com.didauth.common.exception.BusinessException;
import com.didauth.core.entity.CrossChainBridge;
import com.didauth.core.entity.CrossChainTransfer;
import com.didauth.core.mapper.CrossChainBridgeMapper;
import com.didauth.core.mapper.CrossChainTransferMapper;
import com.didauth.module.crosschain.dto.InitiateTransferRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CrossChainService {

    private final CrossChainBridgeMapper bridgeMapper;
    private final CrossChainTransferMapper transferMapper;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    public Mono<String> registerBridge(String sourceChain, String targetChain, String assetSymbol,
                                       String assetAddress, String bridgeContract) {
        return Mono.fromCallable(() -> {
            ChainType.fromCode(sourceChain);
            ChainType.fromCode(targetChain);

            String bridgeId = "bridge_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

            CrossChainBridge bridge = new CrossChainBridge();
            bridge.setBridgeId(bridgeId);
            bridge.setSourceChain(sourceChain.toUpperCase());
            bridge.setTargetChain(targetChain.toUpperCase());
            bridge.setAssetSymbol(assetSymbol.toUpperCase());
            bridge.setAssetAddress(assetAddress);
            bridge.setBridgeContract(bridgeContract);
            bridge.setStatus("ACTIVE");

            bridgeMapper.insert(bridge);

            meterRegistry.counter("crosschain.bridge.register.count").increment();

            return bridgeId;
        });
    }

    @Transactional
    public Mono<String> initiateTransfer(InitiateTransferRequest request) {
        return Mono.fromCallable(() -> {
            ChainType.fromCode(request.getSourceChain());
            ChainType.fromCode(request.getTargetChain());

            String transferId = "transfer_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

            CrossChainBridge bridge;
            if (request.getBridgeId() != null) {
                bridge = bridgeMapper.selectOne(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CrossChainBridge>()
                                .eq(CrossChainBridge::getBridgeId, request.getBridgeId()));
                if (bridge == null) {
                    throw BusinessException.notFound("Bridge not found: " + request.getBridgeId());
                }
            } else {
                bridge = bridgeMapper.selectOne(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CrossChainBridge>()
                                .eq(CrossChainBridge::getSourceChain, request.getSourceChain().toUpperCase())
                                .eq(CrossChainBridge::getTargetChain, request.getTargetChain().toUpperCase())
                                .eq(CrossChainBridge::getAssetSymbol, request.getAssetSymbol().toUpperCase())
                                .eq(CrossChainBridge::getStatus, "ACTIVE"));
                if (bridge == null) {
                    throw BusinessException.paramError("No active bridge found for this cross-chain pair");
                }
            }

            CrossChainTransfer transfer = new CrossChainTransfer();
            transfer.setTransferId(transferId);
            transfer.setBridgeId(bridge.getBridgeId());
            transfer.setSourceChain(request.getSourceChain().toUpperCase());
            transfer.setTargetChain(request.getTargetChain().toUpperCase());
            transfer.setSenderAddress(request.getSenderAddress());
            transfer.setRecipientAddress(request.getRecipientAddress());
            transfer.setAmount(request.getAmount());
            transfer.setAssetSymbol(request.getAssetSymbol().toUpperCase());
            transfer.setStatus("INITIATED");

            transferMapper.insert(transfer);

            meterRegistry.counter("crosschain.transfer.initiate.count",
                    "source", request.getSourceChain(),
                    "target", request.getTargetChain(),
                    "asset", request.getAssetSymbol()).increment();

            log.info("Cross-chain transfer initiated: transferId={}, {} -> {}, amount={}",
                    transferId, request.getSourceChain(), request.getTargetChain(), request.getAmount());

            return transferId;
        });
    }

    @Transactional
    public Mono<String> confirmSourceTransaction(String transferId, String sourceTxHash) {
        return Mono.fromCallable(() -> {
            CrossChainTransfer transfer = getTransfer(transferId);

            if (!"INITIATED".equals(transfer.getStatus())) {
                throw BusinessException.paramError("Transfer is not in INITIATED state");
            }

            transfer.setSourceTxHash(sourceTxHash);
            transfer.setStatus("SOURCE_CONFIRMED");
            transferMapper.updateById(transfer);

            meterRegistry.counter("crosschain.transfer.source_confirmed.count").increment();

            return "SOURCE_CONFIRMED";
        });
    }

    @Transactional
    public Mono<String> verifyMessageProof(String transferId, String messageProof) {
        return Mono.fromCallable(() -> {
            CrossChainTransfer transfer = getTransfer(transferId);

            if (!"SOURCE_CONFIRMED".equals(transfer.getStatus())) {
                throw BusinessException.paramError("Transfer is not in SOURCE_CONFIRMED state");
            }

            boolean verified = verifyCrossChainProof(transfer, messageProof);

            if (!verified) {
                transfer.setStatus("FAILED");
                transfer.setErrorMessage("Message proof verification failed");
                transferMapper.updateById(transfer);
                throw BusinessException.paramError("Cross-chain message proof verification failed");
            }

            transfer.setMessageProof(messageProof);
            transfer.setStatus("PROOF_VERIFIED");
            transferMapper.updateById(transfer);

            meterRegistry.counter("crosschain.transfer.proof_verified.count").increment();

            return "PROOF_VERIFIED";
        });
    }

    private boolean verifyCrossChainProof(CrossChainTransfer transfer, String messageProof) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String combined = transfer.getTransferId() + transfer.getAmount() + transfer.getSourceTxHash();
            byte[] expectedHash = digest.digest(combined.getBytes());
            StringBuilder hexString = new StringBuilder();
            for (byte b : expectedHash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return messageProof.contains(hexString.toString());
        } catch (Exception e) {
            return false;
        }
    }

    @Transactional
    public Mono<String> executeMint(String transferId, String targetTxHash) {
        return Mono.fromCallable(() -> {
            CrossChainTransfer transfer = getTransfer(transferId);

            if (!"PROOF_VERIFIED".equals(transfer.getStatus())) {
                throw BusinessException.paramError("Transfer is not in PROOF_VERIFIED state");
            }

            transfer.setTargetTxHash(targetTxHash);
            transfer.setStatus("COMPLETED");
            transferMapper.updateById(transfer);

            meterRegistry.counter("crosschain.transfer.completed.count").increment();

            log.info("Cross-chain transfer completed: transferId={}", transferId);

            return "COMPLETED";
        });
    }

    public Mono<CrossChainTransfer> getTransferStatus(String transferId) {
        return Mono.fromCallable(() -> getTransfer(transferId));
    }

    public Mono<List<CrossChainTransfer>> listTransfers(String bridgeId, String status, String sourceChain, String targetChain) {
        return Mono.fromCallable(() -> {
            var wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CrossChainTransfer>();
            if (bridgeId != null) wrapper.eq(CrossChainTransfer::getBridgeId, bridgeId);
            if (status != null) wrapper.eq(CrossChainTransfer::getStatus, status);
            if (sourceChain != null) wrapper.eq(CrossChainTransfer::getSourceChain, sourceChain.toUpperCase());
            if (targetChain != null) wrapper.eq(CrossChainTransfer::getTargetChain, targetChain.toUpperCase());
            wrapper.orderByDesc(CrossChainTransfer::getCreatedAt);
            return transferMapper.selectList(wrapper);
        });
    }

    public Mono<List<CrossChainBridge>> listBridges(String sourceChain, String targetChain, String assetSymbol) {
        return Mono.fromCallable(() -> {
            var wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CrossChainBridge>();
            if (sourceChain != null) wrapper.eq(CrossChainBridge::getSourceChain, sourceChain.toUpperCase());
            if (targetChain != null) wrapper.eq(CrossChainBridge::getTargetChain, targetChain.toUpperCase());
            if (assetSymbol != null) wrapper.eq(CrossChainBridge::getAssetSymbol, assetSymbol.toUpperCase());
            wrapper.eq(CrossChainBridge::getStatus, "ACTIVE");
            return bridgeMapper.selectList(wrapper);
        });
    }

    private CrossChainTransfer getTransfer(String transferId) {
        CrossChainTransfer transfer = transferMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CrossChainTransfer>()
                        .eq(CrossChainTransfer::getTransferId, transferId));
        if (transfer == null) {
            throw BusinessException.notFound("Transfer not found: " + transferId);
        }
        return transfer;
    }
}
