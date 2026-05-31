package com.web3platform.chaininteraction.service;

import com.web3platform.chaininteraction.config.ChainInteractionConfig;
import com.web3platform.chaininteraction.config.ChainInteractionConfig.ChainConfig;
import com.web3platform.chaininteraction.observability.ChainInteractionMetrics;
import com.web3platform.chaininteraction.observability.RpcCallTracer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChainClientFactory {

    private final ChainInteractionConfig chainInteractionConfig;
    private final ChainInteractionMetrics chainInteractionMetrics;
    private final RpcCallTracer rpcCallTracer;
    private final Map<String, ChainClient> clients = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        for (ChainConfig chainConfig : chainInteractionConfig.getChains()) {
            String chainType = chainConfig.getChainType();
            if ("EVM".equalsIgnoreCase(chainType)) {
                EvmChainClient evmClient = new EvmChainClient(chainConfig.getChainId(), chainConfig.getRpcUrl());
                evmClient.setMetrics(chainInteractionMetrics);
                evmClient.setTracer(rpcCallTracer);
                clients.put(chainConfig.getChainId(), evmClient);
                chainInteractionMetrics.updateActiveConnections(chainConfig.getChainId(), 1);
                log.info("Registered EVM chain client: chainId={}, rpcUrl={}", chainConfig.getChainId(), chainConfig.getRpcUrl());
            } else {
                log.warn("Unsupported chain type: {} for chainId: {}, skipping", chainType, chainConfig.getChainId());
            }
        }
    }

    public ChainClient getClient(String chainId) {
        ChainClient client = clients.get(chainId);
        if (client == null) {
            throw new IllegalArgumentException("No client found for chainId: " + chainId);
        }
        return client;
    }

    public Set<String> getRegisteredChainIds() {
        return clients.keySet();
    }
}
