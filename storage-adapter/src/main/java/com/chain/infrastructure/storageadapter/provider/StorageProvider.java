package com.chain.infrastructure.storageadapter.provider;

import com.chain.infrastructure.storageadapter.dto.StoreRequest;
import com.chain.infrastructure.storageadapter.dto.StoreResult;
import reactor.core.publisher.Mono;

public interface StorageProvider {

    String getName();

    Mono<StoreResult> store(StoreRequest request);

    Mono<byte[]> retrieve(String cid);

    Mono<Boolean> pin(String cid);

    Mono<Boolean> unpin(String cid);

    Mono<String> getGatewayUrl(String cid);
}
