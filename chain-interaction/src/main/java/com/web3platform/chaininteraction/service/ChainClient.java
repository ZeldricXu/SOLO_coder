package com.web3platform.chaininteraction.service;

import com.web3platform.chaininteraction.model.TransactionReceipt;
import com.web3platform.chaininteraction.model.UnifiedBlock;
import com.web3platform.chaininteraction.model.UnifiedTransaction;

public interface ChainClient {

    UnifiedBlock getBlockByNumber(String chainId, long blockNumber);

    UnifiedBlock getBlockByHash(String chainId, String blockHash);

    UnifiedTransaction getTransactionByHash(String chainId, String txHash);

    TransactionReceipt submitRawTransaction(String chainId, String signedTxHex);

    String getChainId(String chainId);

    long getLatestBlockNumber(String chainId);
}
