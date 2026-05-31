package com.web3platform.chaininteraction.service;

import com.web3platform.chaininteraction.model.EventLog;
import com.web3platform.chaininteraction.model.TransactionReceipt;
import com.web3platform.chaininteraction.model.UnifiedBlock;
import com.web3platform.chaininteraction.model.UnifiedTransaction;
import com.web3platform.chaininteraction.observability.ChainInteractionMetrics;
import com.web3platform.chaininteraction.observability.RpcCallTracer;
import com.web3platform.chaininteraction.observability.RpcSpan;
import lombok.extern.slf4j.Slf4j;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.EthBlockNumber;
import org.web3j.protocol.core.methods.response.EthChainId;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.Transaction;
import org.web3j.protocol.http.HttpService;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
public class EvmChainClient implements ChainClient {

    private final Map<String, Web3j> connections = new ConcurrentHashMap<>();
    private ChainInteractionMetrics metrics;
    private RpcCallTracer tracer;

    public EvmChainClient(String chainId, String rpcUrl) {
        addConnection(chainId, rpcUrl);
    }

    public void setMetrics(ChainInteractionMetrics metrics) {
        this.metrics = metrics;
    }

    public void setTracer(RpcCallTracer tracer) {
        this.tracer = tracer;
    }

    public void addConnection(String chainId, String rpcUrl) {
        HttpService httpService = new HttpService(rpcUrl);
        connections.put(chainId, Web3j.build(httpService));
        if (metrics != null) {
            metrics.updateActiveConnections(chainId, connections.size());
        }
    }

    private Web3j getWeb3j(String chainId) {
        Web3j web3j = connections.get(chainId);
        if (web3j == null) {
            throw new IllegalArgumentException("No connection found for chainId: " + chainId);
        }
        return web3j;
    }

    @Override
    public UnifiedBlock getBlockByNumber(String chainId, long blockNumber) {
        String method = "getBlockByNumber";
        RpcSpan span = startSpan(chainId, method);
        recordRequest(chainId, method);
        long startTime = System.currentTimeMillis();
        try {
            Web3j web3j = getWeb3j(chainId);
            EthBlock ethBlock = web3j.ethGetBlockByNumber(
                    DefaultBlockParameter.valueOf(BigInteger.valueOf(blockNumber)), true)
                    .send();
            UnifiedBlock result = convertBlock(chainId, ethBlock.getBlock());
            long duration = System.currentTimeMillis() - startTime;
            recordDuration(chainId, method, duration);
            endSpan(span, "SUCCESS");
            if (result != null) {
                updateLatestBlock(chainId, result.getBlockNumber());
            }
            return result;
        } catch (IOException e) {
            long duration = System.currentTimeMillis() - startTime;
            recordDuration(chainId, method, duration);
            recordError(chainId, method, "IO_ERROR");
            endSpan(span, "FAILED", e.getMessage());
            throw new RuntimeException("Failed to get block by number for chain: " + chainId, e);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            recordDuration(chainId, method, duration);
            recordError(chainId, method, "UNKNOWN_ERROR");
            endSpan(span, "FAILED", e.getMessage());
            throw e;
        }
    }

    @Override
    public UnifiedBlock getBlockByHash(String chainId, String blockHash) {
        String method = "getBlockByHash";
        RpcSpan span = startSpan(chainId, method);
        recordRequest(chainId, method);
        long startTime = System.currentTimeMillis();
        try {
            Web3j web3j = getWeb3j(chainId);
            EthBlock ethBlock = web3j.ethGetBlockByHash(blockHash, true).send();
            UnifiedBlock result = convertBlock(chainId, ethBlock.getBlock());
            long duration = System.currentTimeMillis() - startTime;
            recordDuration(chainId, method, duration);
            endSpan(span, "SUCCESS");
            return result;
        } catch (IOException e) {
            long duration = System.currentTimeMillis() - startTime;
            recordDuration(chainId, method, duration);
            recordError(chainId, method, "IO_ERROR");
            endSpan(span, "FAILED", e.getMessage());
            throw new RuntimeException("Failed to get block by hash for chain: " + chainId, e);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            recordDuration(chainId, method, duration);
            recordError(chainId, method, "UNKNOWN_ERROR");
            endSpan(span, "FAILED", e.getMessage());
            throw e;
        }
    }

    @Override
    public UnifiedTransaction getTransactionByHash(String chainId, String txHash) {
        String method = "getTransactionByHash";
        RpcSpan span = startSpan(chainId, method);
        recordRequest(chainId, method);
        long startTime = System.currentTimeMillis();
        try {
            Web3j web3j = getWeb3j(chainId);
            org.web3j.protocol.core.methods.response.EthTransaction ethTx =
                    web3j.ethGetTransactionByHash(txHash).send();
            Transaction tx = ethTx.getTransaction()
                    .orElseThrow(() -> new RuntimeException("Transaction not found: " + txHash));
            UnifiedTransaction result = convertTransaction(chainId, tx);
            long duration = System.currentTimeMillis() - startTime;
            recordDuration(chainId, method, duration);
            endSpan(span, "SUCCESS");
            return result;
        } catch (IOException e) {
            long duration = System.currentTimeMillis() - startTime;
            recordDuration(chainId, method, duration);
            recordError(chainId, method, "IO_ERROR");
            endSpan(span, "FAILED", e.getMessage());
            throw new RuntimeException("Failed to get transaction for chain: " + chainId, e);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            recordDuration(chainId, method, duration);
            recordError(chainId, method, "UNKNOWN_ERROR");
            endSpan(span, "FAILED", e.getMessage());
            throw e;
        }
    }

    @Override
    public TransactionReceipt submitRawTransaction(String chainId, String signedTxHex) {
        String method = "submitRawTransaction";
        RpcSpan span = startSpan(chainId, method);
        recordRequest(chainId, method);
        long startTime = System.currentTimeMillis();
        try {
            Web3j web3j = getWeb3j(chainId);
            String hexPayload = signedTxHex.startsWith("0x") ? signedTxHex : "0x" + signedTxHex;
            EthSendTransaction sendResult = web3j.ethSendRawTransaction(hexPayload).send();

            if (sendResult.hasError()) {
                long duration = System.currentTimeMillis() - startTime;
                recordDuration(chainId, method, duration);
                recordError(chainId, method, "CHAIN_ERROR");
                endSpan(span, "FAILED", sendResult.getError().getMessage());
                return TransactionReceipt.builder()
                        .status(0)
                        .build();
            }

            String txHash = sendResult.getTransactionHash();
            TransactionReceipt receipt = waitForReceipt(web3j, txHash);
            long duration = System.currentTimeMillis() - startTime;
            recordDuration(chainId, method, duration);
            endSpan(span, "SUCCESS");
            return receipt;
        } catch (IOException e) {
            long duration = System.currentTimeMillis() - startTime;
            recordDuration(chainId, method, duration);
            recordError(chainId, method, "IO_ERROR");
            endSpan(span, "FAILED", e.getMessage());
            throw new RuntimeException("Failed to submit raw transaction for chain: " + chainId, e);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            recordDuration(chainId, method, duration);
            recordError(chainId, method, "UNKNOWN_ERROR");
            endSpan(span, "FAILED", e.getMessage());
            throw e;
        }
    }

    @Override
    public String getChainId(String chainId) {
        String method = "getChainId";
        RpcSpan span = startSpan(chainId, method);
        recordRequest(chainId, method);
        long startTime = System.currentTimeMillis();
        try {
            Web3j web3j = getWeb3j(chainId);
            EthChainId chainIdResponse = web3j.ethChainId().send();
            String result = chainIdResponse.getChainId().toString();
            long duration = System.currentTimeMillis() - startTime;
            recordDuration(chainId, method, duration);
            endSpan(span, "SUCCESS");
            return result;
        } catch (IOException e) {
            long duration = System.currentTimeMillis() - startTime;
            recordDuration(chainId, method, duration);
            recordError(chainId, method, "IO_ERROR");
            endSpan(span, "FAILED", e.getMessage());
            throw new RuntimeException("Failed to get chainId for chain: " + chainId, e);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            recordDuration(chainId, method, duration);
            recordError(chainId, method, "UNKNOWN_ERROR");
            endSpan(span, "FAILED", e.getMessage());
            throw e;
        }
    }

    @Override
    public long getLatestBlockNumber(String chainId) {
        String method = "getLatestBlockNumber";
        RpcSpan span = startSpan(chainId, method);
        recordRequest(chainId, method);
        long startTime = System.currentTimeMillis();
        try {
            Web3j web3j = getWeb3j(chainId);
            EthBlockNumber blockNumber = web3j.ethBlockNumber().send();
            long result = blockNumber.getBlockNumber().longValue();
            long duration = System.currentTimeMillis() - startTime;
            recordDuration(chainId, method, duration);
            endSpan(span, "SUCCESS");
            updateLatestBlock(chainId, result);
            return result;
        } catch (IOException e) {
            long duration = System.currentTimeMillis() - startTime;
            recordDuration(chainId, method, duration);
            recordError(chainId, method, "IO_ERROR");
            endSpan(span, "FAILED", e.getMessage());
            throw new RuntimeException("Failed to get latest block number for chain: " + chainId, e);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            recordDuration(chainId, method, duration);
            recordError(chainId, method, "UNKNOWN_ERROR");
            endSpan(span, "FAILED", e.getMessage());
            throw e;
        }
    }

    private RpcSpan startSpan(String chainId, String method) {
        if (tracer != null) {
            return tracer.startSpan(chainId, method);
        }
        return null;
    }

    private void endSpan(RpcSpan span, String status) {
        endSpan(span, status, null);
    }

    private void endSpan(RpcSpan span, String status, String error) {
        if (span != null && tracer != null) {
            span.setError(error);
            tracer.endSpan(span, status);
        }
    }

    private void recordRequest(String chainId, String method) {
        if (metrics != null) {
            metrics.recordRequest(chainId, method);
        }
    }

    private void recordDuration(String chainId, String method, long durationMs) {
        if (metrics != null) {
            metrics.recordDuration(chainId, method, durationMs);
        }
    }

    private void recordError(String chainId, String method, String errorType) {
        if (metrics != null) {
            metrics.recordError(chainId, method, errorType);
        }
    }

    private void updateLatestBlock(String chainId, long blockNumber) {
        if (metrics != null) {
            metrics.updateLatestBlock(chainId, blockNumber);
        }
    }

    private TransactionReceipt waitForReceipt(Web3j web3j, String txHash) throws IOException {
        int maxAttempts = 60;
        int attempt = 0;
        int pollIntervalMs = 2000;

        while (attempt < maxAttempts) {
            EthGetTransactionReceipt receiptResponse = web3j.ethGetTransactionReceipt(txHash).send();
            if (receiptResponse.getTransactionReceipt().isPresent()) {
                return convertReceipt(receiptResponse.getTransactionReceipt().get());
            }
            attempt++;
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for transaction receipt", e);
            }
        }
        throw new RuntimeException("Transaction receipt not received after " + maxAttempts + " attempts, txHash: " + txHash);
    }

    private UnifiedBlock convertBlock(String chainId, EthBlock.Block block) {
        if (block == null) {
            return null;
        }

        List<UnifiedTransaction> transactions;
        if (block.getTransactions() != null) {
            transactions = block.getTransactions().stream()
                    .map(txResult -> {
                        if (txResult instanceof EthBlock.TransactionObject txObj) {
                            return convertTransaction(chainId, txObj.get());
                        }
                        return null;
                    })
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toList());
        } else {
            transactions = Collections.emptyList();
        }

        return UnifiedBlock.builder()
                .chainId(chainId)
                .blockNumber(block.getNumber().longValue())
                .blockHash(block.getHash())
                .parentHash(block.getParentHash())
                .timestamp(block.getTimestamp().longValue())
                .transactions(transactions)
                .build();
    }

    private UnifiedTransaction convertTransaction(String chainId, Transaction tx) {
        return UnifiedTransaction.builder()
                .chainId(chainId)
                .txHash(tx.getHash())
                .blockNumber(tx.getBlockNumber() != null ? tx.getBlockNumber().longValue() : 0)
                .fromAddr(tx.getFrom())
                .toAddr(tx.getTo())
                .value(tx.getValue())
                .gasUsed(0)
                .status(-1)
                .inputData(tx.getInput())
                .build();
    }

    private TransactionReceipt convertReceipt(org.web3j.protocol.core.methods.response.TransactionReceipt receipt) {
        List<EventLog> logs = receipt.getLogs().stream()
                .map(log -> EventLog.builder()
                        .address(log.getAddress())
                        .topics(log.getTopics())
                        .data(log.getData())
                        .build())
                .collect(Collectors.toList());

        return TransactionReceipt.builder()
                .txHash(receipt.getTransactionHash())
                .blockNumber(receipt.getBlockNumber().longValue())
                .gasUsed(receipt.getGasUsed().longValue())
                .status(receipt.getStatus().equals("0x1") ? 1 : 0)
                .logs(logs)
                .build();
    }
}
