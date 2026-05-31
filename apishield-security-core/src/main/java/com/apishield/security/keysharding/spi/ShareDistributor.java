package com.apishield.security.keysharding.spi;

import com.apishield.security.keysharding.domain.KeyShare;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public interface ShareDistributor {
    CompletableFuture<Boolean> distribute(KeyShare share, String targetEndpoint);
    CompletableFuture<Boolean> distributeBatch(List<KeyShare> shares, Map<String, String> endpointMap);
}
