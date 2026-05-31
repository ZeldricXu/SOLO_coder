package com.web3platform.chaininteraction.observability;

import com.web3platform.chaininteraction.service.ChainClient;
import com.web3platform.chaininteraction.service.ChainClientFactory;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class ChainInteractionObservabilityConfig {

    private final ChainClientFactory chainClientFactory;
    private final ChainInteractionMetrics chainInteractionMetrics;
    private ScheduledExecutorService scheduledExecutorService;

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> metricsCommonTags() {
        return registry -> registry.config().commonTags("application", "chain-interaction");
    }

    @Bean
    public ScheduledExecutorService observabilityScheduledExecutor() {
        scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "observability-metrics");
            t.setDaemon(true);
            return t;
        });
        return scheduledExecutorService;
    }

    @PostConstruct
    public void initMetricsUpdater() {
        scheduledExecutorService.scheduleAtFixedRate(this::updateLatestBlockGauges, 30, 30, TimeUnit.SECONDS);
        log.info("Observability metrics updater initialized with 30s interval");
    }

    private void updateLatestBlockGauges() {
        for (String chainId : chainClientFactory.getRegisteredChainIds()) {
            try {
                ChainClient client = chainClientFactory.getClient(chainId);
                long latestBlock = client.getLatestBlockNumber(chainId);
                chainInteractionMetrics.updateLatestBlock(chainId, latestBlock);
            } catch (Exception e) {
                log.warn("Failed to update latest block gauge for chain: {}", chainId, e);
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        if (scheduledExecutorService != null) {
            scheduledExecutorService.shutdown();
        }
    }
}
