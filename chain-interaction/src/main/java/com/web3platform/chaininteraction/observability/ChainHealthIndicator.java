package com.web3platform.chaininteraction.observability;

import com.web3platform.chaininteraction.service.ChainClient;
import com.web3platform.chaininteraction.service.ChainClientFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChainHealthIndicator extends AbstractHealthIndicator {

    private final ChainClientFactory chainClientFactory;

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        Map<String, Object> chainDetails = new LinkedHashMap<>();
        boolean allUp = true;

        for (String chainId : chainClientFactory.getRegisteredChainIds()) {
            Map<String, Object> detail = checkChain(chainId);
            chainDetails.put(chainId, detail);
            if (!Boolean.TRUE.equals(detail.get("connected"))) {
                allUp = false;
            }
        }

        if (allUp) {
            builder.up();
        } else {
            builder.down();
        }

        builder.withDetail("chains", chainDetails);
    }

    private Map<String, Object> checkChain(String chainId) {
        Map<String, Object> detail = new LinkedHashMap<>();
        try {
            ChainClient client = chainClientFactory.getClient(chainId);
            long startTime = System.currentTimeMillis();
            long latestBlock = client.getLatestBlockNumber(chainId);
            long responseTime = System.currentTimeMillis() - startTime;

            detail.put("chainId", chainId);
            detail.put("connected", true);
            detail.put("latestBlock", latestBlock);
            detail.put("responseTime", responseTime);
        } catch (Exception e) {
            log.warn("Health check failed for chain: {}", chainId, e);
            detail.put("chainId", chainId);
            detail.put("connected", false);
            detail.put("latestBlock", -1);
            detail.put("responseTime", -1);
            detail.put("error", e.getMessage());
        }
        return detail;
    }
}
