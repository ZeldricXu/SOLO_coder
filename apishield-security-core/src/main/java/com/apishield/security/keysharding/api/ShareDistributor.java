package com.apishield.security.keysharding.api;

import com.apishield.security.keysharding.domain.KeyShare;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public interface ShareDistributor {
    CompletableFuture<Boolean> distributeShare(KeyShare share, String endpoint);
    CompletableFuture<Boolean> distributeShares(List<KeyShare> shares, Map<String, String> endpointMap);
}
