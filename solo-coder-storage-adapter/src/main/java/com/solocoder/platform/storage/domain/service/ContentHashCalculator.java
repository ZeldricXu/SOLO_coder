package com.solocoder.platform.storage.domain.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ContentHashCalculator {

    private static final String HASH_ALGORITHM = "SHA-256";
    private static final String IPFS_PREFIX = "Qm";

    public String calculateContentId(byte[] content, String storageType) {
        return switch (storageType.toUpperCase()) {
            case "IPFS" -> generateIpfsCid();
            case "ARWEAVE" -> generateArweaveTxId();
            case "FILECOIN" -> generateFilecoinCid();
            default -> generateDefaultCid();
        };
    }

    public String calculateContentHash(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hashBytes = digest.digest(content);
            return "0x" + HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            log.error("计算内容哈希失败", e);
            throw new RuntimeException("哈希算法不可用", e);
        }
    }

    public String calculateContentHash(InputStream content, long size) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = content.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            byte[] hashBytes = digest.digest();
            return "0x" + HexFormat.of().formatHex(hashBytes);
        } catch (Exception e) {
            log.error("计算内容哈希失败", e);
            throw new RuntimeException("计算内容哈希失败", e);
        }
    }

    private String generateIpfsCid() {
        return IPFS_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 44);
    }

    private String generateArweaveTxId() {
        return "ar://" + UUID.randomUUID().toString().replace("-", "");
    }

    private String generateFilecoinCid() {
        return "bafy" + UUID.randomUUID().toString().replace("-", "").substring(0, 40);
    }

    private String generateDefaultCid() {
        return "cid://" + UUID.randomUUID();
    }

    public String getGatewayUrl(String contentId, String storageType) {
        return switch (storageType.toUpperCase()) {
            case "IPFS" -> "https://ipfs.io/ipfs/" + contentId;
            case "ARWEAVE" -> "https://arweave.net/" + contentId.replace("ar://", "");
            case "FILECOIN" -> "https://dweb.link/ipfs/" + contentId;
            default -> "https://gateway.solocoder.com/content/" + contentId;
        };
    }
}
