package com.apishield.audit.domain.service;

import com.apishield.common.util.CryptoUtil;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class HashChainService {

    private static final String GENESIS_HASH = "GENESIS_BLOCK_HASH";
    private final AtomicReference<String> lastHash = new AtomicReference<>(GENESIS_HASH);
    private final AtomicInteger blockHeight = new AtomicInteger(0);

    public String calculateHash(String logId, String operation, String operatorId,
                                 String resourceType, String resourceId, long timestamp,
                                 String previousHash) {
        String content = logId + "|" +
                operation + "|" +
                operatorId + "|" +
                resourceType + "|" +
                resourceId + "|" +
                timestamp + "|" +
                previousHash;
        return CryptoUtil.sha256(content);
    }

    public String getNextHash(String logId, String operation, String operatorId,
                              String resourceType, String resourceId, long timestamp) {
        String prevHash = lastHash.get();
        String newHash = calculateHash(logId, operation, operatorId, resourceType,
                                        resourceId, timestamp, prevHash);
        lastHash.set(newHash);
        blockHeight.incrementAndGet();
        return newHash;
    }

    public String getLastHash() {
        return lastHash.get();
    }

    public int getCurrentBlockHeight() {
        return blockHeight.get();
    }

    public void resetChain() {
        lastHash.set(GENESIS_HASH);
        blockHeight.set(0);
    }
}
