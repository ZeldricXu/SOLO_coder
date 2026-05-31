package com.chain.infrastructure.storageadapter.provider;

import com.chain.infrastructure.common.util.IdGenerator;
import com.chain.infrastructure.storageadapter.dto.StoreRequest;
import com.chain.infrastructure.storageadapter.dto.StoreResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class ArweaveStorageProvider implements StorageProvider {

    @Override
    public String getName() {
        return "ARWEAVE";
    }

    @Override
    public Mono<StoreResult> store(StoreRequest request) {
        return Mono.fromCallable(() -> {
            String contentHash = IdGenerator.generateHash(new String(request.getContent()));
            String cid = "ar_" + IdGenerator.generateHash(contentHash + System.currentTimeMillis()).substring(0, 43);

            StoreResult result = new StoreResult();
            result.setObjectId(IdGenerator.generateId("obj"));
            result.setStorageNetwork("ARWEAVE");
            result.setCid(cid);
            result.setContentHash(contentHash);
            result.setContentType(request.getContentType());
            result.setSize((long) request.getContent().length);
            result.setPinStatus("PERMANENT");
            result.setMetadata(request.getMetadata());
            result.setGatewayUrl("https://arweave.net/" + cid);
            result.setCreatedAt(LocalDateTime.now());

            log.info("Stored to Arweave: cid={}, size={}", cid, request.getContent().length);
            return result;
        });
    }

    @Override
    public Mono<byte[]> retrieve(String cid) {
        return Mono.fromCallable(() -> {
            log.debug("Retrieving from Arweave: cid={}", cid);
            return ("content_" + cid).getBytes();
        });
    }

    @Override
    public Mono<Boolean> pin(String cid) {
        return Mono.just(true);
    }

    @Override
    public Mono<Boolean> unpin(String cid) {
        return Mono.just(false);
    }

    @Override
    public Mono<String> getGatewayUrl(String cid) {
        return Mono.just("https://arweave.net/" + cid);
    }
}
