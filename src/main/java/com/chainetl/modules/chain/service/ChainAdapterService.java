package com.chainetl.modules.chain.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chainetl.common.exception.BusinessException;
import com.chainetl.common.util.IdGenerator;
import com.chainetl.modules.chain.dto.BlockData;
import com.chainetl.modules.chain.dto.RpcNodeConfig;
import com.chainetl.modules.chain.dto.SubmitTransactionRequest;
import com.chainetl.modules.chain.dto.TransactionData;
import com.chainetl.modules.chain.mapper.ChainNodeMapper;
import com.chainetl.modules.chain.model.ChainNode;
import com.chainetl.modules.chain.rpc.Web3jRpcClient;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChainAdapterService {

    private final ChainNodeMapper chainNodeMapper;
    private final Web3jRpcClient web3jRpcClient;

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_INACTIVE = "INACTIVE";
    private static final String STATUS_UNHEALTHY = "UNHEALTHY";

    @Transactional
    @Timed(value = "chain.node.register", description = "Time taken to register a chain node")
    public Mono<ChainNode> registerNode(RpcNodeConfig config) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<ChainNode> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ChainNode::getChainId, config.getChainId())
                    .eq(ChainNode::getRpcUrl, config.getRpcUrl());

            ChainNode existing = chainNodeMapper.selectOne(wrapper);
            if (existing != null) {
                existing.setStatus(STATUS_ACTIVE);
                existing.setWsUrl(config.getWsUrl());
                existing.setUpdatedAt(Instant.now());
                chainNodeMapper.updateById(existing);
                web3jRpcClient.registerNode(config.getChainId(), config.getRpcUrl());
                log.info("Updated existing chain node: {} for chain: {}", existing.getNodeId(), config.getChainId());
                return existing;
            }

            String nodeId = IdGenerator.generateNodeId();
            Instant now = Instant.now();

            ChainNode node = ChainNode.builder()
                    .nodeId(nodeId)
                    .chainId(config.getChainId())
                    .rpcUrl(config.getRpcUrl())
                    .wsUrl(config.getWsUrl())
                    .status(STATUS_ACTIVE)
                    .priority(0)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();

            chainNodeMapper.insert(node);
            web3jRpcClient.registerNode(config.getChainId(), config.getRpcUrl());
            log.info("Registered new chain node: {} for chain: {}", nodeId, config.getChainId());

            return node;
        });
    }

    @Timed(value = "chain.node.list", description = "Time taken to list chain nodes")
    public Mono<List<ChainNode>> listNodes(String chainId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<ChainNode> wrapper = new LambdaQueryWrapper<>();
            if (chainId != null) {
                wrapper.eq(ChainNode::getChainId, chainId);
            }
            wrapper.orderByAsc(ChainNode::getChainId)
                    .orderByDesc(ChainNode::getPriority);

            return chainNodeMapper.selectList(wrapper);
        });
    }

    @Timed(value = "chain.block.getByNumber", description = "Time taken to get block by number")
    @Retry(name = "chainAdapter", fallbackMethod = "getBlockByNumberFallback")
    @CircuitBreaker(name = "chainAdapter")
    public Mono<BlockData> getBlockByNumber(String chainId, Long blockNumber) {
        validateChainHasActiveNodes(chainId);
        return web3jRpcClient.getBlockByNumber(chainId, BigInteger.valueOf(blockNumber));
    }

    @Timed(value = "chain.block.getByHash", description = "Time taken to get block by hash")
    @Retry(name = "chainAdapter", fallbackMethod = "getBlockByHashFallback")
    @CircuitBreaker(name = "chainAdapter")
    public Mono<BlockData> getBlockByHash(String chainId, String blockHash) {
        validateChainHasActiveNodes(chainId);
        return web3jRpcClient.getBlockByHash(chainId, blockHash);
    }

    @Timed(value = "chain.transaction.get", description = "Time taken to get transaction")
    @Retry(name = "chainAdapter", fallbackMethod = "getTransactionByHashFallback")
    @CircuitBreaker(name = "chainAdapter")
    public Mono<TransactionData> getTransactionByHash(String chainId, String txHash) {
        validateChainHasActiveNodes(chainId);
        return web3jRpcClient.getTransactionByHash(chainId, txHash);
    }

    @Timed(value = "chain.block.getLatest", description = "Time taken to get latest block")
    @Retry(name = "chainAdapter", fallbackMethod = "getLatestBlockNumberFallback")
    @CircuitBreaker(name = "chainAdapter")
    public Mono<BigInteger> getLatestBlockNumber(String chainId) {
        validateChainHasActiveNodes(chainId);
        return web3jRpcClient.getLatestBlockNumber(chainId);
    }

    @Timed(value = "chain.transaction.submit", description = "Time taken to submit transaction")
    @Retry(name = "chainAdapter", fallbackMethod = "submitTransactionFallback")
    @CircuitBreaker(name = "chainAdapter")
    public Mono<String> submitTransaction(SubmitTransactionRequest request) {
        validateChainHasActiveNodes(request.getChainId());
        return web3jRpcClient.sendRawTransaction(request.getChainId(), request.getSignedTx());
    }

    @Timed(value = "chain.transaction.getReceipt", description = "Time taken to get transaction receipt")
    public Mono<String> getTransactionReceipt(String chainId, String txHash) {
        validateChainHasActiveNodes(chainId);
        return web3jRpcClient.getTransactionReceipt(chainId, txHash);
    }

    @Timed(value = "chain.gas.estimate", description = "Time taken to estimate gas")
    public Mono<BigInteger> estimateGas(String chainId, String from, String to, String data) {
        validateChainHasActiveNodes(chainId);
        return web3jRpcClient.estimateGas(chainId, from, to, data);
    }

    private void validateChainHasActiveNodes(String chainId) {
        LambdaQueryWrapper<ChainNode> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChainNode::getChainId, chainId)
                .eq(ChainNode::getStatus, STATUS_ACTIVE);

        Long count = chainNodeMapper.selectCount(wrapper);
        if (count == null || count == 0) {
            throw new BusinessException(404, "No active RPC nodes available for chain: " + chainId);
        }
    }

    @Scheduled(fixedRate = 30000)
    @Timed(value = "chain.node.healthCheck", description = "Time taken to perform node health check")
    public void performHealthCheck() {
        log.debug("Starting chain node health check");

        LambdaQueryWrapper<ChainNode> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(ChainNode::getStatus, STATUS_ACTIVE, STATUS_UNHEALTHY);

        List<ChainNode> nodes = chainNodeMapper.selectList(wrapper);

        Flux.fromIterable(nodes)
                .flatMap(node -> checkNodeHealth(node)
                        .onErrorResume(e -> {
                            log.warn("Health check failed for node {}: {}", node.getNodeId(), e.getMessage());
                            return Mono.empty();
                        }))
                .subscribe();
    }

    private Mono<ChainNode> checkNodeHealth(ChainNode node) {
        return Mono.fromCallable(() -> {
                    long start = System.currentTimeMillis();
                    web3jRpcClient.registerNode(node.getChainId(), node.getRpcUrl());
                    web3jRpcClient.getLatestBlockNumber(node.getChainId()).block();
                    long latency = System.currentTimeMillis() - start;

                    node.setLatency(latency);
                    node.setStatus(STATUS_ACTIVE);
                    node.setLastChecked(Instant.now());
                    node.setUpdatedAt(Instant.now());

                    chainNodeMapper.updateById(node);
                    log.debug("Node {} is healthy, latency: {}ms", node.getNodeId(), latency);

                    return node;
                })
                .onErrorResume(e -> {
                    node.setStatus(STATUS_UNHEALTHY);
                    node.setLastChecked(Instant.now());
                    node.setUpdatedAt(Instant.now());
                    chainNodeMapper.updateById(node);
                    log.warn("Marked node {} as unhealthy: {}", node.getNodeId(), e.getMessage());
                    return Mono.just(node);
                });
    }

    public ChainNode getBestNode(String chainId) {
        LambdaQueryWrapper<ChainNode> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChainNode::getChainId, chainId)
                .eq(ChainNode::getStatus, STATUS_ACTIVE)
                .isNotNull(ChainNode::getLatency);

        List<ChainNode> nodes = chainNodeMapper.selectList(wrapper);

        return nodes.stream()
                .min(Comparator.comparingLong(ChainNode::getLatency)
                        .thenComparingInt(ChainNode::getPriority).reversed())
                .orElseThrow(() -> new BusinessException(404, "No healthy nodes available for chain: " + chainId));
    }

    private Mono<BlockData> getBlockByNumberFallback(String chainId, Long blockNumber, Exception e) {
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

    private Mono<String> submitTransactionFallback(SubmitTransactionRequest request, Exception e) {
        log.error("submitTransaction fallback triggered for chain {}: {}", request.getChainId(), e.getMessage());
        throw new BusinessException("Failed to submit transaction after retries: " + e.getMessage());
    }
}
