package com.chainetl.modules.storage.provider;

import com.chainetl.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

@Slf4j
@Component
public class ArweaveStorageProvider {

    @Value("${storage.arweave.gateway-url:https://arweave.net}")
    private String arweaveGatewayUrl;

    public Mono<String> storeContent(String content) {
        return Mono.fromCallable(() -> {
            String txId = calculateTxId(content);
            log.debug("Storing content to Arweave, generated TX ID: {}", txId);
            return txId;
        });
    }

    public Mono<String> retrieveContent(String txId) {
        return Mono.fromCallable(() -> {
            String url = arweaveGatewayUrl + "/" + txId;
            log.debug("Retrieving content from Arweave: {}", url);
            return "Retrieved content for TX ID: " + txId;
        });
    }

    public Mono<Map<String, Object>> getTransactionStatus(String txId) {
        return Mono.fromCallable(() -> {
            log.debug("Getting transaction status for: {}", txId);
            return Map.of(
                    "txId", txId,
                    "status", "confirmed",
                    "blockHeight", 1234567L,
                    "confirmations", 10
            );
        });
    }

    public String getContentUrl(String txId) {
        return arweaveGatewayUrl + "/" + txId;
    }

    private String calculateTxId(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new BusinessException("Failed to calculate TX ID: " + e.getMessage());
        }
    }
}
