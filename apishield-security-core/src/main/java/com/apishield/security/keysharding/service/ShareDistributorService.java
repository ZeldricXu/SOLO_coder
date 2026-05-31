package com.apishield.security.keysharding.service;

import com.apishield.security.keysharding.api.ShareDistributor;
import com.apishield.security.keysharding.domain.KeyShare;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
public class ShareDistributorService implements ShareDistributor {

    private final com.apishield.security.keysharding.spi.ShareDistributor distributor;

    @Override
    public CompletableFuture<Boolean> distributeShare(KeyShare share, String endpoint) {
        if (share == null) throw new IllegalArgumentException("Share cannot be null");
        if (endpoint == null || endpoint.isBlank()) throw new IllegalArgumentException("Endpoint cannot be blank");
        return distributor.distribute(share, endpoint);
    }

    @Override
    public CompletableFuture<Boolean> distributeShares(List<KeyShare> shares, Map<String, String> endpointMap) {
        if (shares == null || shares.isEmpty()) throw new IllegalArgumentException("Shares cannot be empty");
        if (endpointMap == null || endpointMap.isEmpty()) throw new IllegalArgumentException("Endpoint map cannot be empty");
        return distributor.distributeBatch(shares, endpointMap);
    }
}
