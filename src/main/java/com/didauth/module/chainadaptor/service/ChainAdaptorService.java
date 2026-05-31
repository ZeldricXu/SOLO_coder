package com.didauth.module.chainadaptor.service;

import com.didauth.common.enums.ChainType;
import com.didauth.common.exception.BusinessException;
import com.didauth.core.entity.ChainRpcNode;
import com.didauth.core.mapper.ChainRpcNodeMapper;
import com.didauth.module.chainadaptor.dto.RpcRequest;
import com.didauth.module.chainadaptor.dto.RpcResponse;
import com.didauth.module.chainadaptor.dto.SendTransactionRequest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChainAdaptorService {

    private final ChainRpcNodeMapper rpcNodeMapper;
    private final MeterRegistry meterRegistry;
    private final WebClient.Builder webClientBuilder;

    @Value("${didauth.chain.rpc.eth:https://eth.llamarpc.com}")
    private String ethRpcUrl;

    @Value("${didauth.chain.rpc.polygon:https://polygon-rpc.com}")
    private String polygonRpcUrl;

    @Value("${didauth.chain.rpc.bsc:https://bsc-dataseed.binance.org}")
    private String bscRpcUrl;

    private final Map<String, String> rpcUrlCache = new ConcurrentHashMap<>();
    private final Map<String, Long> chainIdCache = new ConcurrentHashMap<>();

    public Mono<String> getBlockNumber(String chainType) {
        return callRpc(chainType, "eth_blockNumber", new Object[]{})
                .map(response -> (String) response.getResult());
    }

    public Mono<String> getBlockByNumber(String chainType, String blockNumber, boolean fullTx) {
        return callRpc(chainType, "eth_getBlockByNumber", new Object[]{blockNumber, fullTx})
                .map(response -> response.getResult() != null ? response.getResult().toString() : null);
    }

    public Mono<String> getTransactionByHash(String chainType, String txHash) {
        return callRpc(chainType, "eth_getTransactionByHash", new Object[]{txHash})
                .map(response -> response.getResult() != null ? response.getResult().toString() : null);
    }

    public Mono<String> getTransactionReceipt(String chainType, String txHash) {
        return callRpc(chainType, "eth_getTransactionReceipt", new Object[]{txHash})
                .map(response -> response.getResult() != null ? response.getResult().toString() : null);
    }

    public Mono<String> getBalance(String chainType, String address, String blockTag) {
        return callRpc(chainType, "eth_getBalance", new Object[]{address, blockTag != null ? blockTag : "latest"})
                .map(response -> (String) response.getResult());
    }

    public Mono<String> getNonce(String chainType, String address) {
        return callRpc(chainType, "eth_getTransactionCount", new Object[]{address, "pending"})
                .map(response -> (String) response.getResult());
    }

    public Mono<String> sendRawTransaction(SendTransactionRequest request) {
        return callRpc(request.getChainType(), "eth_sendRawTransaction", new Object[]{request.getSignedTx()})
                .map(response -> {
                    if (response.getError() != null) {
                        throw new RuntimeException("RPC error: " + response.getError().getMessage());
                    }
                    String txHash = (String) response.getResult();
                    meterRegistry.counter("chain.transaction.sent", "chain", request.getChainType()).increment();
                    return txHash;
                });
    }

    public Mono<String> call(String chainType, String method, Object[] params) {
        return callRpc(chainType, method, params)
                .map(response -> {
                    if (response.getError() != null) {
                        throw new RuntimeException("RPC error: " + response.getError().getMessage());
                    }
                    return response.getResult() != null ? response.getResult().toString() : null;
                });
    }

    @SuppressWarnings("unchecked")
    private Mono<RpcResponse<Object>> callRpc(String chainType, String method, Object[] params) {
        ChainType type = ChainType.fromCode(chainType);
        String rpcUrl = getRpcUrl(type);

        RpcRequest request = new RpcRequest();
        request.setMethod(method);
        request.setParams(params);
        request.setId((int) (System.currentTimeMillis() % Integer.MAX_VALUE));

        Timer.Sample sample = Timer.start(meterRegistry);

        return webClientBuilder.build()
                .post()
                .uri(rpcUrl)
                .bodyValue(request)
                .retrieve()
                .onStatus(HttpStatusCode::isError, clientResponse ->
                        clientResponse.bodyToMono(String.class)
                                .flatMap(error -> Mono.error(new RuntimeException("HTTP error: " + error))))
                .bodyToMono(RpcResponse.class)
                .retryWhen(Retry.backoff(2, Duration.ofMillis(100))
                        .filter(e -> e.getMessage() != null && e.getMessage().contains("timeout")))
                .doOnSuccess(response -> {
                    long latency = sample.stop(Timer.builder("chain.rpc.duration")
                            .tag("chain", type.getCode())
                            .tag("method", method)
                            .tag("result", response.getError() != null ? "error" : "success")
                            .register(meterRegistry));
                    log.debug("RPC call: chain={}, method={}, latency={}ms", type.getCode(), method, latency / 1_000_000);
                })
                .doOnError(e -> {
                    sample.stop(Timer.builder("chain.rpc.duration")
                            .tag("chain", type.getCode())
                            .tag("method", method)
                            .tag("result", "error")
                            .register(meterRegistry));
                    log.error("RPC call failed: chain={}, method={}, error={}", type.getCode(), method, e.getMessage());
                });
    }

    private String getRpcUrl(ChainType chainType) {
        return rpcUrlCache.computeIfAbsent(chainType.getCode(), k -> {
            List<ChainRpcNode> nodes = rpcNodeMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChainRpcNode>()
                            .eq(ChainRpcNode::getChainType, chainType.getCode())
                            .eq(ChainRpcNode::getIsActive, true)
                            .orderByDesc(ChainRpcNode::getPriority));

            if (!nodes.isEmpty()) {
                return nodes.get(0).getRpcUrl();
            }

            return switch (chainType) {
                case ETH -> ethRpcUrl;
                case POLYGON -> polygonRpcUrl;
                case BSC -> bscRpcUrl;
                default -> ethRpcUrl;
            };
        });
    }

    public Mono<List<ChainRpcNode>> listRpcNodes(String chainType) {
        return Mono.fromCallable(() -> {
            var wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChainRpcNode>();
            if (chainType != null) wrapper.eq(ChainRpcNode::getChainType, chainType);
            wrapper.orderByDesc(ChainRpcNode::getPriority);
            return rpcNodeMapper.selectList(wrapper);
        });
    }

    public Mono<String> addRpcNode(ChainRpcNode node) {
        return Mono.fromCallable(() -> {
            node.setIsActive(true);
            node.setHealthStatus("UNKNOWN");
            rpcNodeMapper.insert(node);
            rpcUrlCache.remove(node.getChainType());
            return node.getId();
        });
    }

    public Mono<Void> deleteRpcNode(String id) {
        return Mono.fromCallable(() -> {
            ChainRpcNode node = rpcNodeMapper.selectById(id);
            if (node != null) {
                rpcUrlCache.remove(node.getChainType());
            }
            rpcNodeMapper.deleteById(id);
            return null;
        });
    }

    public Mono<String> getChainId(String chainType) {
        ChainType type = ChainType.fromCode(chainType);
        return Mono.just("0x" + Long.toHexString(type.getChainId()));
    }
}
