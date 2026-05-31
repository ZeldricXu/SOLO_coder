package com.chain.infrastructure.chainadapter.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.chain.infrastructure.chainadapter.dto.RpcRequest;
import com.chain.infrastructure.chainadapter.dto.RpcResponse;
import com.chain.infrastructure.chainadapter.dto.SubmitTransactionRequest;
import com.chain.infrastructure.common.util.JsonUtils;
import com.chain.infrastructure.persistence.entity.RpcNode;
import com.chain.infrastructure.persistence.mapper.RpcNodeMapper;
import io.netty.handler.timeout.ReadTimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChainRpcService {

    private final RpcNodeMapper rpcNodeMapper;
    private final WebClient.Builder webClientBuilder;

    public Mono<RpcNode> selectNode(String chainType) {
        return Mono.fromCallable(() -> {
            QueryWrapper<RpcNode> wrapper = new QueryWrapper<>();
            wrapper.eq("chain_type", chainType)
                    .eq("enabled", true)
                    .eq("health_status", "HEALTHY")
                    .orderByDesc("priority");

            List<RpcNode> nodes = rpcNodeMapper.selectList(wrapper);
            if (nodes.isEmpty()) {
                throw new IllegalStateException("No healthy RPC nodes available for chain: " + chainType);
            }

            int totalWeight = nodes.stream().mapToInt(RpcNode::getWeight).sum();
            int random = ThreadLocalRandom.current().nextInt(totalWeight);
            int current = 0;

            for (RpcNode node : nodes) {
                current += node.getWeight();
                if (random < current) {
                    return node;
                }
            }

            return nodes.get(0);
        });
    }

    public <T> Mono<RpcResponse<T>> callRpc(String chainType, String method, Object... params) {
        return selectNode(chainType)
                .flatMap(node -> callRpcNode(node, method, params));
    }

    @SuppressWarnings("unchecked")
    private <T> Mono<RpcResponse<T>> callRpcNode(RpcNode node, String method, Object... params) {
        RpcRequest request = new RpcRequest(method, params);
        String requestBody = JsonUtils.toJson(request);

        log.debug("Calling RPC: node={}, method={}", node.getName(), method);

        return webClientBuilder.build()
                .post()
                .uri(node.getRpcUrl())
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .map(body -> {
                    RpcResponse<T> response = JsonUtils.fromJson(body, RpcResponse.class);
                    if (response.getError() != null) {
                        log.warn("RPC error: node={}, method={}, code={}, message={}",
                                node.getName(), method, response.getError().getCode(), response.getError().getMessage());
                    }
                    updateNodeHealth(node, true);
                    return response;
                })
                .timeout(Duration.ofMillis(node.getTimeoutMs()))
                .onErrorResume(e -> {
                    log.error("RPC call failed: node={}, method={}, error={}",
                            node.getName(), method, e.getMessage());
                    updateNodeHealth(node, false);
                    return Mono.error(e);
                });
    }

    private void updateNodeHealth(RpcNode node, boolean healthy) {
        node.setHealthStatus(healthy ? "HEALTHY" : "UNHEALTHY");
        node.setLastHealthCheck(LocalDateTime.now());
        rpcNodeMapper.updateById(node);
    }

    public Mono<String> getBlockNumber(String chainType) {
        return this.<String>callRpc(chainType, "eth_blockNumber")
                .map(RpcResponse::getResult);
    }

    public Mono<String> getGasPrice(String chainType) {
        return this.<String>callRpc(chainType, "eth_gasPrice")
                .map(RpcResponse::getResult);
    }

    public Mono<String> getTransactionCount(String chainType, String address) {
        return this.<String>callRpc(chainType, "eth_getTransactionCount", address, "latest")
                .map(RpcResponse::getResult);
    }

    public Mono<String> sendRawTransaction(String chainType, String signedTx) {
        return this.<String>callRpc(chainType, "eth_sendRawTransaction", signedTx)
                .map(RpcResponse::getResult);
    }

    public Mono<String> submitTransaction(SubmitTransactionRequest request) {
        return sendRawTransaction(request.getChainType(), request.getSignedTransaction())
                .doOnSuccess(txHash -> log.info("Transaction submitted: chain={}, txHash={}",
                        request.getChainType(), txHash));
    }

    public Mono<String> getTransactionReceipt(String chainType, String txHash) {
        return this.<String>callRpc(chainType, "eth_getTransactionReceipt", txHash)
                .map(resp -> JsonUtils.toJson(resp.getResult()));
    }

    public Mono<String> getBlockByNumber(String chainType, Long blockNumber, boolean fullTx) {
        String hexBlock = "0x" + Long.toHexString(blockNumber);
        return this.<String>callRpc(chainType, "eth_getBlockByNumber", hexBlock, fullTx)
                .map(resp -> JsonUtils.toJson(resp.getResult()));
    }

    public Mono<RpcNode> registerNode(RpcNode node) {
        return Mono.fromCallable(() -> {
            node.setNodeId("node_" + System.currentTimeMillis());
            node.setHealthStatus("UNKNOWN");
            rpcNodeMapper.insert(node);
            return node;
        });
    }

    public Mono<List<RpcNode>> getNodes(String chainType) {
        return Mono.fromCallable(() -> {
            QueryWrapper<RpcNode> wrapper = new QueryWrapper<>();
            wrapper.eq("chain_type", chainType);
            return rpcNodeMapper.selectList(wrapper);
        });
    }

    public Mono<Void> healthCheckAllNodes() {
        return Mono.fromCallable(() -> {
            QueryWrapper<RpcNode> wrapper = new QueryWrapper<>();
            wrapper.eq("enabled", true);
            List<RpcNode> nodes = rpcNodeMapper.selectList(wrapper);

            nodes.forEach(node -> {
                try {
                    callRpcNode(node, "eth_blockNumber").block(Duration.ofMillis(5000));
                } catch (Exception e) {
                    log.warn("Health check failed for node: {}", node.getName());
                }
            });
            return null;
        });
    }
}
