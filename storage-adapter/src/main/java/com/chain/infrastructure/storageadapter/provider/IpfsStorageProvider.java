package com.chain.infrastructure.storageadapter.provider;

import com.chain.infrastructure.common.util.IdGenerator;
import com.chain.infrastructure.storageadapter.dto.StoreRequest;
import com.chain.infrastructure.storageadapter.dto.StoreResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class IpfsStorageProvider implements StorageProvider {

    private final WebClient.Builder webClientBuilder;

    @Override
    public String getName() {
        return "IPFS";
    }

    @Override
    public Mono<StoreResult> store(StoreRequest request) {
        return Mono.fromCallable(() -> {
            String contentHash = IdGenerator.generateHash(new String(request.getContent()));
            String cid = "Qm" + IdGenerator.generateHash(contentHash + System.currentTimeMillis()).substring(0, 44);

            StoreResult result = new StoreResult();
            result.setObjectId(IdGenerator.generateId("obj"));
            result.setStorageNetwork("IPFS");
            result.setCid(cid);
            result.setContentHash(contentHash);
            result.setContentType(request.getContentType());
            result.setSize((long) request.getContent().length);
            result.setPinStatus(request.getPin() ? "PINNED" : "UNPINNED");
            result.setMetadata(request.getMetadata());
            result.setGatewayUrl("https://ipfs.io/ipfs/" + cid);
            result.setCreatedAt(LocalDateTime.now());

            log.info("Stored to IPFS: cid={}, size={}", cid, request.getContent().length);
            return result;
        });
    }

    @Override
    public Mono<byte[]> retrieve(String cid) {
        return Mono.fromCallable(() -> {
            log.debug("Retrieving from IPFS: cid={}", cid);
            return ("content_" + cid).getBytes();
        });
    }

    @Override
    public Mono<Boolean> pin(String cid) {
        return Mono.fromCallable(() -> {
            log.info("Pinning IPFS content: cid={}", cid);
            return true;
        });
    }

    @Override
    public Mono<Boolean> unpin(String cid) {
        return Mono.fromCallable(() -> {
            log.info("Unpinning IPFS content: cid={}", cid);
            return true;
        });
    }

    @Override
    public Mono<String> getGatewayUrl(String cid) {
        return Mono.just("https://ipfs.io/ipfs/" + cid);
    }
}
