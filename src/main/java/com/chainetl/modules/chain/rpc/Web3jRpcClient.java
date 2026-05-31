package com.chainetl.modules.chain.rpc;

import com.alibaba.fastjson2.JSON;
import com.chainetl.common.exception.BusinessException;
import com.chainetl.modules.chain.dto.BlockData;
import com.chainetl.modules.chain.dto.TransactionData;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;
import reactor.core.publisher.Mono;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Component
public class Web3jRpcClient implements RpcClient {

    private final Map<String, Web3j> web3jClients = new ConcurrentHashMap<>();

    private Web3j getWeb3j(String rpcUrl) {
        return web3jClients.computeIfAbsent(rpcUrl, url -> {
            log.debug("Creating new Web3j client for: {}", url);
            return Web3j.build(new HttpService(url));
        });
    }

    public void registerNode(String chainId, String rpcUrl) {
        getWeb3j(rpcUrl);
        log.info("Registered Web3j client for chain: {}, rpc: {}", chainId, rpcUrl);
    }

    @Override
    @Retry(name = "chainRpc", fallbackMethod = "getBlockByNumberFallback")
    @CircuitBreaker(name = "chainRpc")
    public Mono<BlockData> getBlockByNumber(String chainId, BigInteger blockNumber) {
        return Mono.fromCallable(() -> {
            String rpcUrl = getRpcUrlForChain(chainId);
            Web3j web3j = getWeb3j(rpcUrl);

            EthBlock block = web3j.ethGetBlockByNumber(
                    DefaultBlockParameter.valueOf(blockNumber),
                    true
            ).send();

            if (block.hasError()) {
                throw new BusinessException("RPC error: " + block.getError().getMessage());
            }

            return convertToBlockData(chainId, block.getBlock());
        });
    }

    @Override
    @Retry(name = "chainRpc", fallbackMethod = "getBlockByHashFallback")
    @CircuitBreaker(name = "chainRpc")
    public Mono<BlockData> getBlockByHash(String chainId, String blockHash) {
        return Mono.fromCallable(() -> {
            String rpcUrl = getRpcUrlForChain(chainId);
            Web3j web3j = getWeb3j(rpcUrl);

            EthBlock block = web3j.ethGetBlockByHash(blockHash, true).send();

            if (block.hasError()) {
                throw new BusinessException("RPC error: " + block.getError().getMessage());
            }

            return convertToBlockData(chainId, block.getBlock());
        });
    }

    @Override
    @Retry(name = "chainRpc", fallbackMethod = "getTransactionByHashFallback")
    @CircuitBreaker(name = "chainRpc")
    public Mono<TransactionData> getTransactionByHash(String chainId, String txHash) {
        return Mono.fromCallable(() -> {
            String rpcUrl = getRpcUrlForChain(chainId);
            Web3j web3j = getWeb3j(rpcUrl);

            org.web3j.protocol.core.methods.response.Transaction tx =
                    web3j.ethGetTransactionByHash(txHash).send().getTransaction().orElse(null);

            if (tx == null) {
                throw new BusinessException(404, "Transaction not found: " + txHash);
            }

            return convertToTransactionData(chainId, tx);
        });
    }

    @Override
    @Retry(name = "chainRpc", fallbackMethod = "getLatestBlockNumberFallback")
    @CircuitBreaker(name = "chainRpc")
    public Mono<BigInteger> getLatestBlockNumber(String chainId) {
        return Mono.fromCallable(() -> {
            String rpcUrl = getRpcUrlForChain(chainId);
            Web3j web3j = getWeb3j(rpcUrl);

            return web3j.ethBlockNumber().send().getBlockNumber();
        });
    }

    @Override
    @Retry(name = "chainRpc", fallbackMethod = "sendRawTransactionFallback")
    @CircuitBreaker(name = "chainRpc")
    public Mono<String> sendRawTransaction(String chainId, String signedTx) {
        return Mono.fromCallable(() -> {
            String rpcUrl = getRpcUrlForChain(chainId);
            Web3j web3j = getWeb3j(rpcUrl);

            EthSendTransaction response = web3j.ethSendRawTransaction(signedTx).send();

            if (response.hasError()) {
                throw new BusinessException("RPC error: " + response.getError().getMessage());
            }

            return response.getTransactionHash();
        });
    }

    @Override
    @Retry(name = "chainRpc", fallbackMethod = "getTransactionReceiptFallback")
    @CircuitBreaker(name = "chainRpc")
    public Mono<String> getTransactionReceipt(String chainId, String txHash) {
        return Mono.fromCallable(() -> {
            String rpcUrl = getRpcUrlForChain(chainId);
            Web3j web3j = getWeb3j(rpcUrl);

            EthGetTransactionReceipt response = web3j.ethGetTransactionReceipt(txHash).send();

            if (response.hasError()) {
                throw new BusinessException("RPC error: " + response.getError().getMessage());
            }

            TransactionReceipt receipt = response.getTransactionReceipt().orElse(null);
            return receipt != null ? JSON.toJSONString(receipt) : null;
        });
    }

    @Override
    @Retry(name = "chainRpc", fallbackMethod = "estimateGasFallback")
    @CircuitBreaker(name = "chainRpc")
    public Mono<BigInteger> estimateGas(String chainId, String from, String to, String data) {
        return Mono.fromCallable(() -> {
            String rpcUrl = getRpcUrlForChain(chainId);
            Web3j web3j = getWeb3j(rpcUrl);

            Transaction tx = Transaction.createFunctionCallTransaction(from, null, null, null, to, null, data);
            return web3j.ethEstimateGas(tx).send().getAmountUsed();
        });
    }

    private String getRpcUrlForChain(String chainId) {
        if (web3jClients.isEmpty()) {
            throw new BusinessException(404, "No RPC nodes registered for chain: " + chainId);
        }
        return web3jClients.keySet().iterator().next();
    }

    private BlockData convertToBlockData(String chainId, EthBlock.Block block) {
        List<TransactionData> transactions = block.getTransactions().stream()
                .map(txResult -> {
                    if (txResult.get() instanceof org.web3j.protocol.core.methods.response.Transaction) {
                        return convertToTransactionData(chainId,
                                (org.web3j.protocol.core.methods.response.Transaction) txResult.get());
                    }
                    return null;
                })
                .filter(tx -> tx != null)
                .collect(Collectors.toList());

        return BlockData.builder()
                .chainId(chainId)
                .blockNumber(block.getNumber().longValue())
                .blockHash(block.getHash())
                .parentHash(block.getParentHash())
                .timestamp(Instant.ofEpochSecond(block.getTimestamp().longValue()))
                .transactions(transactions)
                .rawData(JSON.toJSONString(block))
                .build();
    }

    private TransactionData convertToTransactionData(String chainId,
                                                     org.web3j.protocol.core.methods.response.Transaction tx) {
        return TransactionData.builder()
                .chainId(chainId)
                .txHash(tx.getHash())
                .fromAddress(tx.getFrom())
                .toAddress(tx.getTo())
                .value(tx.getValue())
                .gasLimit(tx.getGas())
                .gasPrice(tx.getGasPrice())
                .nonce(tx.getNonce())
                .inputData(tx.getInput())
                .status(tx.getStatus() != null ? tx.getStatus().toString() : null)
                .build();
    }

    private Mono<BlockData> getBlockByNumberFallback(String chainId, BigInteger blockNumber, Exception e) {
        log.error("getBlockByNumber fallback triggered for chain {}, block {}: {}", chainId, blockNumber, e.getMessage());
        throw new BusinessException("Failed to get block after retries: " + e.getMessage());
    }

    private Mono<BlockData> getBlockByHashFallback(String chainId, String blockHash, Exception e) {
        log.error("getBlockByHash fallback triggered for chain {}, hash {}: {}", chainId, blockHash, e.getMessage());
        throw new BusinessException("Failed to get block after retries: " + e.getMessage());
    }

    private Mono<TransactionData> getTransactionByHashFallback(String chainId, String txHash, Exception e) {
        log.error("getTransactionByHash fallback triggered for chain {}, tx {}: {}", chainId, txHash, e.getMessage());
        throw new BusinessException("Failed to get transaction after retries: " + e.getMessage());
    }

    private Mono<BigInteger> getLatestBlockNumberFallback(String chainId, Exception e) {
        log.error("getLatestBlockNumber fallback triggered for chain {}: {}", chainId, e.getMessage());
        throw new BusinessException("Failed to get latest block after retries: " + e.getMessage());
    }

    private Mono<String> sendRawTransactionFallback(String chainId, String signedTx, Exception e) {
        log.error("sendRawTransaction fallback triggered for chain {}: {}", chainId, e.getMessage());
        throw new BusinessException("Failed to send transaction after retries: " + e.getMessage());
    }

    private Mono<String> getTransactionReceiptFallback(String chainId, String txHash, Exception e) {
        log.error("getTransactionReceipt fallback triggered for chain {}, tx {}: {}", chainId, txHash, e.getMessage());
        throw new BusinessException("Failed to get transaction receipt after retries: " + e.getMessage());
    }

    private Mono<BigInteger> estimateGasFallback(String chainId, String from, String to, String data, Exception e) {
        log.error("estimateGas fallback triggered for chain {}: {}", chainId, e.getMessage());
        throw new BusinessException("Failed to estimate gas after retries: " + e.getMessage());
    }
}
