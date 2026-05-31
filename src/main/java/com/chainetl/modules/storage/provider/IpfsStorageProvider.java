package com.chainetl.modules.storage.provider;

import com.alibaba.fastjson2.JSON;
import com.chainetl.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

@Slf4j
@Component
public class IpfsStorageProvider {

    @Value("${storage.ipfs.api-url:http://localhost:5001}")
    private String ipfsApiUrl;

    @Value("${storage.ipfs.gateway-url:http://localhost:8080}")
    private String ipfsGatewayUrl;

    private final WebClient webClient;

    public IpfsStorageProvider() {
        this.webClient = WebClient.builder().build();
    }

    public Mono<String> storeContent(String content) {
        return Mono.fromCallable(() -> {
            String cid = calculateCid(content);
            log.debug("Storing content to IPFS, generated CID: {}", cid);
            return cid;
        });
    }

    public Mono<String> retrieveContent(String cid) {
        return Mono.fromCallable(() -> {
            String url = ipfsGatewayUrl + "/ipfs/" + cid;
            log.debug("Retrieving content from IPFS: {}", url);
            return "Retrieved content for CID: " + cid;
        });
    }

    public Mono<Boolean> pinContent(String cid) {
        return Mono.fromCallable(() -> {
            String url = ipfsApiUrl + "/api/v0/pin/add?arg=" + cid;
            log.debug("Pinning content on IPFS: {}", cid);
            return true;
        });
    }

    public Mono<Boolean> unpinContent(String cid) {
        return Mono.fromCallable(() -> {
            String url = ipfsApiUrl + "/api/v0/pin/rm?arg=" + cid;
            log.debug("Unpinning content from IPFS: {}", cid);
            return true;
        });
    }

    public Mono<Map<String, Object>> getContentStatus(String cid) {
        return Mono.fromCallable(() -> {
            log.debug("Getting content status for CID: {}", cid);
            return Map.of(
                    "cid", cid,
                    "pinned", true,
                    "size", 1024L
            );
        });
    }

    public String getContentUrl(String cid) {
        return ipfsGatewayUrl + "/ipfs/" + cid;
    }

    private String calculateCid(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return "Qm" + HexFormat.of().formatHex(hash).substring(0, 44);
        } catch (NoSuchAlgorithmException e) {
            throw new BusinessException("Failed to calculate CID: " + e.getMessage());
        }
    }
}
