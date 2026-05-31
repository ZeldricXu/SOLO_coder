package com.web3platform.crosschainbridge.pool;

import lombok.Data;
import org.web3j.protocol.Web3j;

@Data
public class PooledRpcConnection {

    private Web3j web3j;
    private long lastUsedTime;
    private boolean inUse;
    private String chainId;

    public PooledRpcConnection(Web3j web3j, String chainId) {
        this.web3j = web3j;
        this.chainId = chainId;
        this.lastUsedTime = System.currentTimeMillis();
        this.inUse = false;
    }

    public void markInUse() {
        this.inUse = true;
        this.lastUsedTime = System.currentTimeMillis();
    }

    public void markReturned() {
        this.inUse = false;
        this.lastUsedTime = System.currentTimeMillis();
    }
}
