package com.apishield.audit.api;

public interface HashChainManager {
    String getLastHash();
    int getCurrentBlockHeight();
    String calculateHash(String logId, String operation, String operatorId,
                         String resourceType, String resourceId, long timestamp,
                         String previousHash);
    void resetChain();
}
