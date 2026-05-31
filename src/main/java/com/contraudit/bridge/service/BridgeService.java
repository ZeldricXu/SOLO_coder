package com.contraudit.bridge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.contraudit.common.BusinessException;
import com.contraudit.common.ErrorCode;
import com.contraudit.bridge.dto.InitiateTransferRequest;
import com.contraudit.bridge.entity.BridgeChain;
import com.contraudit.bridge.entity.BridgeMessage;
import com.contraudit.bridge.entity.BridgeTransfer;
import com.contraudit.bridge.mapper.BridgeChainMapper;
import com.contraudit.bridge.mapper.BridgeMessageMapper;
import com.contraudit.bridge.mapper.BridgeTransferMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class BridgeService {

    public static final String STATUS_INIT = "INIT";
    public static final String STATUS_LOCKED = "LOCKED";
    public static final String STATUS_VERIFIED = "VERIFIED";
    public static final String STATUS_MINTED = "MINTED";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_EXPIRED = "EXPIRED";

    private static final ThreadLocal<MessageDigest> SHA256_DIGEST = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    });

    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final Cache<Long, BridgeChain> chainCache = Caffeine.newBuilder()
            .maximumSize(100)
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .recordStats()
            .build();

    private final BridgeChainMapper bridgeChainMapper;
    private final BridgeTransferMapper bridgeTransferMapper;
    private final BridgeMessageMapper bridgeMessageMapper;

    @Value("${bridge.timeout:3600}")
    private Integer bridgeTimeout;

    @Value("${bridge.cleanup.days-to-keep:30}")
    private int daysToKeep;

    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public BridgeTransfer initiateTransfer(InitiateTransferRequest request) {
        BridgeChain fromChain = getChain(request.getFromChainId());
        BridgeChain toChain = getChain(request.getToChainId());

        String transferId = generateTransferId();

        BridgeTransfer transfer = new BridgeTransfer();
        transfer.setTransferId(transferId);
        transfer.setFromChainId(request.getFromChainId());
        transfer.setToChainId(request.getToChainId());
        transfer.setFromAddress(request.getFromAddress());
        transfer.setToAddress(request.getToAddress());
        transfer.setTokenAddress(request.getTokenAddress());
        transfer.setTokenSymbol(request.getTokenSymbol());
        transfer.setAmount(request.getAmount());
        transfer.setFee(request.getFee());
        transfer.setStatus(STATUS_INIT);
        transfer.setMessageHash(generateMessageHash(transfer));
        transfer.setExpireAt(LocalDateTime.now().plusSeconds(bridgeTimeout));

        bridgeTransferMapper.insert(transfer);

        BridgeMessage message = createBridgeMessage(transfer);
        bridgeMessageMapper.insert(message);

        log.info("Initiated bridge transfer: {} from chain {} to chain {}",
                transferId, request.getFromChainId(), request.getToChainId());

        return transfer;
    }

    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public BridgeTransfer confirmLock(String transferId, String txHash, Long blockNumber) {
        BridgeTransfer transfer = getTransfer(transferId);

        if (!STATUS_INIT.equals(transfer.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "transfer is not in INIT status");
        }

        checkExpired(transfer);

        transfer.setLockTxHash(txHash);
        transfer.setLockBlockNumber(blockNumber);
        transfer.setStatus(STATUS_LOCKED);
        bridgeTransferMapper.updateById(transfer);

        updateMessageStatus(transfer.getTransferId(), "VERIFIED");

        log.info("Confirmed lock for transfer: {}, tx: {}", transferId, txHash);

        return transfer;
    }

    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public BridgeTransfer confirmMint(String transferId, String txHash, Long blockNumber, String proofData) {
        BridgeTransfer transfer = getTransfer(transferId);

        if (!STATUS_LOCKED.equals(transfer.getStatus()) && !STATUS_VERIFIED.equals(transfer.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "transfer is not ready for mint");
        }

        checkExpired(transfer);

        transfer.setMintTxHash(txHash);
        transfer.setMintBlockNumber(blockNumber);
        transfer.setProofData(proofData);
        transfer.setStatus(STATUS_MINTED);
        bridgeTransferMapper.updateById(transfer);

        updateMessageStatus(transfer.getTransferId(), "DELIVERED");

        log.info("Confirmed mint for transfer: {}, tx: {}", transferId, txHash);

        return transfer;
    }

    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public BridgeTransfer completeTransfer(String transferId) {
        BridgeTransfer transfer = getTransfer(transferId);

        if (!STATUS_MINTED.equals(transfer.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "transfer is not minted yet");
        }

        transfer.setStatus(STATUS_CONFIRMED);
        bridgeTransferMapper.updateById(transfer);

        log.info("Completed bridge transfer: {}", transferId);

        return transfer;
    }

    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public BridgeTransfer failTransfer(String transferId, String errorMessage) {
        BridgeTransfer transfer = getTransfer(transferId);
        transfer.setStatus(STATUS_FAILED);
        transfer.setErrorMessage(errorMessage);
        bridgeTransferMapper.updateById(transfer);
        log.error("Bridge transfer failed: {}, error: {}", transferId, errorMessage);
        return transfer;
    }

    public BridgeTransfer getTransfer(String transferId) {
        LambdaQueryWrapper<BridgeTransfer> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BridgeTransfer::getTransferId, transferId);
        BridgeTransfer transfer = bridgeTransferMapper.selectOne(wrapper);
        if (transfer == null) {
            throw new BusinessException(ErrorCode.BRIDGE_TRANSFER_NOT_FOUND);
        }
        checkExpired(transfer);
        return transfer;
    }

    public IPage<BridgeTransfer> listTransfers(Long fromChainId, Long toChainId, String status,
                                               String address, int page, int size) {
        int actualSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int actualPage = Math.max(page, 1);

        LambdaQueryWrapper<BridgeTransfer> wrapper = new LambdaQueryWrapper<>();
        if (fromChainId != null) {
            wrapper.eq(BridgeTransfer::getFromChainId, fromChainId);
        }
        if (toChainId != null) {
            wrapper.eq(BridgeTransfer::getToChainId, toChainId);
        }
        if (status != null) {
            wrapper.eq(BridgeTransfer::getStatus, status);
        }
        if (address != null) {
            wrapper.and(w -> w.eq(BridgeTransfer::getFromAddress, address)
                    .or().eq(BridgeTransfer::getToAddress, address));
        }
        wrapper.orderByDesc(BridgeTransfer::getCreatedAt);

        return bridgeTransferMapper.selectPage(new Page<>(actualPage, actualSize), wrapper);
    }

    public List<BridgeTransfer> listTransfers(Long fromChainId, Long toChainId, String status, String address) {
        return listTransfers(fromChainId, toChainId, status, address, 1, DEFAULT_PAGE_SIZE).getRecords();
    }

    public BridgeChain getChain(Long chainId) {
        return chainCache.get(chainId, this::loadChainFromDb);
    }

    private BridgeChain loadChainFromDb(Long chainId) {
        LambdaQueryWrapper<BridgeChain> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BridgeChain::getChainId, chainId);
        wrapper.eq(BridgeChain::getStatus, 1);
        BridgeChain chain = bridgeChainMapper.selectOne(wrapper);
        if (chain == null) {
            throw new BusinessException(ErrorCode.BRIDGE_CHAIN_NOT_SUPPORTED, "chain id: " + chainId);
        }
        return chain;
    }

    public List<BridgeChain> listSupportedChains() {
        LambdaQueryWrapper<BridgeChain> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BridgeChain::getStatus, 1);
        List<BridgeChain> chains = bridgeChainMapper.selectList(wrapper);
        chains.forEach(chain -> chainCache.put(chain.getChainId(), chain));
        return chains;
    }

    public boolean verifyMessage(String messageId, String signature) {
        LambdaQueryWrapper<BridgeMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BridgeMessage::getMessageId, messageId);
        BridgeMessage message = bridgeMessageMapper.selectOne(wrapper);

        if (message == null) {
            throw new BusinessException(ErrorCode.BRIDGE_INVALID_MESSAGE);
        }

        boolean valid = verifySignature(message.getPayload(), signature);
        if (valid) {
            message.setStatus("VERIFIED");
            message.setVerifiedAt(LocalDateTime.now());
            bridgeMessageMapper.updateById(message);
        }

        return valid;
    }

    private String generateTransferId() {
        return "bridge_" + System.currentTimeMillis() + "_" +
                UUID.randomUUID().toString().substring(0, 8);
    }

    private String generateMessageHash(BridgeTransfer transfer) {
        try {
            String content = transfer.getTransferId() + transfer.getFromChainId() +
                    transfer.getToChainId() + transfer.getFromAddress() +
                    transfer.getToAddress() + transfer.getTokenAddress() +
                    transfer.getAmount();

            MessageDigest digest = SHA256_DIGEST.get();
            digest.reset();
            byte[] hash = digest.digest(content.getBytes());
            return HexFormat.of().formatHex(hash);
        } finally {
        }
    }

    private BridgeMessage createBridgeMessage(BridgeTransfer transfer) {
        BridgeMessage message = new BridgeMessage();
        message.setMessageId("msg_" + transfer.getTransferId());
        message.setFromChainId(transfer.getFromChainId());
        message.setToChainId(transfer.getToChainId());
        message.setMessageType("TRANSFER");
        message.setPayload(transfer.getMessageHash());
        message.setStatus("PENDING");
        message.setNonce(System.currentTimeMillis());
        return message;
    }

    private void updateMessageStatus(String transferId, String status) {
        LambdaQueryWrapper<BridgeMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BridgeMessage::getMessageId, "msg_" + transferId);
        BridgeMessage message = bridgeMessageMapper.selectOne(wrapper);
        if (message != null) {
            message.setStatus(status);
            if ("DELIVERED".equals(status)) {
                message.setDeliveredAt(LocalDateTime.now());
            }
            bridgeMessageMapper.updateById(message);
        }
    }

    private boolean verifySignature(String payload, String signature) {
        return signature != null && !signature.isEmpty();
    }

    private void checkExpired(BridgeTransfer transfer) {
        if ((STATUS_INIT.equals(transfer.getStatus()) || STATUS_LOCKED.equals(transfer.getStatus()))
                && transfer.getExpireAt() != null
                && transfer.getExpireAt().isBefore(LocalDateTime.now())) {
            transfer.setStatus(STATUS_EXPIRED);
            bridgeTransferMapper.updateById(transfer);
            log.warn("Bridge transfer expired: {}", transfer.getTransferId());
            throw new BusinessException(ErrorCode.BAD_REQUEST, "transfer has expired");
        }
    }

    @Scheduled(fixedRate = 300000)
    @Transactional(rollbackFor = Exception.class)
    public void expireTransfersTask() {
        log.debug("Running bridge transfer expiry check");
        LambdaQueryWrapper<BridgeTransfer> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(BridgeTransfer::getStatus, List.of(STATUS_INIT, STATUS_LOCKED));
        wrapper.lt(BridgeTransfer::getExpireAt, LocalDateTime.now());
        wrapper.last("LIMIT 100");

        List<BridgeTransfer> expiredTransfers = bridgeTransferMapper.selectList(wrapper);
        expiredTransfers.forEach(t -> {
            t.setStatus(STATUS_EXPIRED);
            bridgeTransferMapper.updateById(t);
            updateMessageStatus(t.getTransferId(), "EXPIRED");
        });

        if (!expiredTransfers.isEmpty()) {
            log.info("Expired {} bridge transfers", expiredTransfers.size());
        }
    }

    @Scheduled(cron = "0 30 2 * * ?")
    @Transactional(rollbackFor = Exception.class)
    public void cleanupOldTransfers() {
        log.info("Running bridge transfer cleanup task");
        LocalDateTime cutoff = LocalDateTime.now().minusDays(daysToKeep);
        LambdaQueryWrapper<BridgeTransfer> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(BridgeTransfer::getStatus, List.of(STATUS_CONFIRMED, STATUS_FAILED, STATUS_EXPIRED));
        wrapper.lt(BridgeTransfer::getUpdatedAt, cutoff);
        wrapper.last("LIMIT 500");

        List<BridgeTransfer> oldTransfers = bridgeTransferMapper.selectList(wrapper);
        if (!oldTransfers.isEmpty()) {
            oldTransfers.forEach(t -> {
                try {
                    LambdaQueryWrapper<BridgeMessage> msgWrapper = new LambdaQueryWrapper<>();
                    msgWrapper.eq(BridgeMessage::getMessageId, "msg_" + t.getTransferId());
                    bridgeMessageMapper.delete(msgWrapper);
                    bridgeTransferMapper.deleteById(t);
                } catch (Exception e) {
                    log.warn("Failed to delete old transfer: {}", t.getTransferId(), e);
                }
            });
            log.info("Cleaned up {} old bridge transfers", oldTransfers.size());
        }

        logCacheStats();
    }

    private void logCacheStats() {
        var stats = chainCache.stats();
        log.debug("Chain cache stats - hits: {}, misses: {}, size: {}",
                stats.hitCount(), stats.missCount(), chainCache.estimatedSize());
    }

    public void evictChainCache(Long chainId) {
        chainCache.invalidate(chainId);
        log.debug("Evicted chain cache for id: {}", chainId);
    }

    public void clearAllCaches() {
        chainCache.invalidateAll();
        chainCache.cleanUp();
        SHA256_DIGEST.remove();
        log.info("Cleared all bridge service caches");
    }
}
