package com.chainetl.modules.chain.rpc;

import com.chainetl.modules.chain.dto.BlockData;
import com.chainetl.modules.chain.dto.TransactionData;
import reactor.core.publisher.Mono;

import java.math.BigInteger;

public interface RpcClient {

    Mono<BlockData> getBlockByNumber(String chainId, BigInteger blockNumber);

    Mono<BlockData> getBlockByHash(String chainId, String blockHash);

    Mono<TransactionData> getTransactionByHash(String chainId, String txHash);

    Mono<BigInteger> getLatestBlockNumber(String chainId);

    Mono<String> sendRawTransaction(String chainId, String signedTx);

    Mono<String> getTransactionReceipt(String chainId, String txHash);

    Mono<BigInteger> estimateGas(String chainId, String from, String to, String data);
}
