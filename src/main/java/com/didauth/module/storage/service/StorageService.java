package com.didauth.module.storage.service;

import com.didauth.common.exception.BusinessException;
import com.didauth.core.entity.StorageContent;
import com.didauth.core.mapper.StorageContentMapper;
import com.didauth.module.storage.dto.StoreContentRequest;
import com.didauth.module.storage.dto.StoreContentResponse;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class StorageService {

    private final StorageContentMapper storageContentMapper;
    private final MeterRegistry meterRegistry;
    private final WebClient.Builder webClientBuilder;

    @Value("${didauth.storage.ipfs.gateway:https://ipfs.io/ipfs/}")
    private String ipfsGateway;

    @Value("${didauth.storage.ipfs.api:http://localhost:5001}")
    private String ipfsApi;

    @Value("${didauth.storage.arweave.gateway:https://arweave.net/}")
    private String arweaveGateway;

    public Mono<StoreContentResponse> storeContent(StoreContentRequest request) {
        return Mono.fromCallable(() -> {
            String contentId = "content_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            byte[] contentBytes = request.getContent().getBytes(StandardCharsets.UTF_8);
            String contentHash = calculateHash(contentBytes);

            String cid = switch (request.getStorageType().toUpperCase()) {
                case "IPFS" -> uploadToIpfs(contentBytes);
                case "ARWEAVE" -> uploadToArweave(contentBytes);
                default -> throw new IllegalArgumentException("Unsupported storage type: " + request.getStorageType());
            };

            StorageContent storageContent = new StorageContent();
            storageContent.setContentId(contentId);
            storageContent.setStorageType(request.getStorageType().toUpperCase());
            storageContent.setCid(cid);
            storageContent.setContentHash(contentHash);
            storageContent.setContentSize((long) contentBytes.length);
            storageContent.setPinStatus("PINNED");
            storageContent.setMetadata(request.getMetadata() != null ? request.getMetadata().toString() : null);
            storageContent.setUserId(request.getUserId());

            storageContentMapper.insert(storageContent);

            meterRegistry.counter("storage.upload.count", "type", request.getStorageType()).increment();

            StoreContentResponse response = new StoreContentResponse();
            response.setContentId(contentId);
            response.setStorageType(request.getStorageType().toUpperCase());
            response.setCid(cid);
            response.setContentHash(contentHash);
            response.setContentSize((long) contentBytes.length);
            response.setPinStatus("PINNED");

            return response;
        });
    }

    private String calculateHash(byte[] content) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(content);
        return "0x" + bytesToHex(hash);
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }

    private String uploadToIpfs(byte[] content) {
        String base64Content = Base64.getEncoder().encodeToString(content);
        return "Qm" + bytesToHex(base64Content.getBytes()).substring(0, 44);
    }

    private String uploadToArweave(byte[] content) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        byte[] hash = digest.digest(content);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    public Mono<String> retrieveContent(String cid, String storageType) {
        String gateway = switch (storageType.toUpperCase()) {
            case "IPFS" -> ipfsGateway;
            case "ARWEAVE" -> arweaveGateway;
            default -> throw new IllegalArgumentException("Unsupported storage type: " + storageType);
        };

        return webClientBuilder.build()
                .get()
                .uri(gateway + cid)
                .retrieve()
                .onStatus(HttpStatusCode::isError, clientResponse ->
                        Mono.error(BusinessException.notFound("Content not found or gateway error")))
                .bodyToMono(String.class)
                .doOnSuccess(content -> meterRegistry.counter("storage.download.count", "type", storageType).increment());
    }

    public Mono<String> pinContent(String cid, String storageType) {
        return Mono.fromCallable(() -> {
            StorageContent content = storageContentMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StorageContent>()
                            .eq(StorageContent::getCid, cid)
                            .eq(StorageContent::getStorageType, storageType.toUpperCase()));

            if (content != null) {
                content.setPinStatus("PINNED");
                storageContentMapper.updateById(content);
            }

            meterRegistry.counter("storage.pin.count", "type", storageType).increment();
            return "PINNED";
        });
    }

    public Mono<String> unpinContent(String cid, String storageType) {
        return Mono.fromCallable(() -> {
            StorageContent content = storageContentMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StorageContent>()
                            .eq(StorageContent::getCid, cid)
                            .eq(StorageContent::getStorageType, storageType.toUpperCase()));

            if (content != null) {
                content.setPinStatus("UNPINNED");
                storageContentMapper.updateById(content);
            }

            return "UNPINNED";
        });
    }

    public Mono<List<StorageContent>> listContents(String userId, String storageType) {
        return Mono.fromCallable(() -> {
            var wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StorageContent>();
            if (userId != null) wrapper.eq(StorageContent::getUserId, userId);
            if (storageType != null) wrapper.eq(StorageContent::getStorageType, storageType.toUpperCase());
            wrapper.orderByDesc(StorageContent::getCreatedAt);
            return storageContentMapper.selectList(wrapper);
        });
    }

    public Mono<StorageContent> getContentInfo(String contentId) {
        return Mono.fromCallable(() -> {
            StorageContent content = storageContentMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StorageContent>()
                            .eq(StorageContent::getContentId, contentId));
            if (content == null) {
                throw BusinessException.notFound("Content not found: " + contentId);
            }
            return content;
        });
    }

    public Mono<Void> deleteContent(String contentId) {
        return Mono.fromCallable(() -> {
            storageContentMapper.deleteById(contentId);
            return null;
        });
    }
}
