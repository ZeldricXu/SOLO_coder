package com.configcenter.encryption.service;

import cn.hutool.crypto.SecureUtil;
import cn.hutool.crypto.symmetric.AES;
import com.configcenter.common.exception.BusinessException;
import com.configcenter.encryption.config.EncryptionProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;

@Slf4j
@Service
@RequiredArgsConstructor
public class EncryptionService {

    private final EncryptionProperties properties;
    
    private AES aes;

    @PostConstruct
    public void init() {
        if (properties.getEnabled()) {
            try {
                byte[] key = SecureUtil.generateKey(properties.getAlgorithm(), properties.getKeySize(), 
                    properties.getSecretKey().getBytes(StandardCharsets.UTF_8)).getEncoded();
                byte[] iv = properties.getIv().getBytes(StandardCharsets.UTF_8);
                this.aes = new AES(properties.getCipherAlgorithm(), key, iv);
                log.info("Encryption service initialized with algorithm: {}", properties.getAlgorithm());
            } catch (Exception e) {
                log.error("Failed to initialize encryption service", e);
                throw new BusinessException("加密服务初始化失败", e);
            }
        }
    }

    public String encrypt(String plainText) {
        if (plainText == null) {
            return null;
        }
        if (!properties.getEnabled()) {
            return plainText;
        }
        try {
            return aes.encryptBase64(plainText);
        } catch (Exception e) {
            log.error("Encryption failed", e);
            throw new BusinessException("加密失败", e);
        }
    }

    public String decrypt(String encryptedText) {
        if (encryptedText == null) {
            return null;
        }
        if (!properties.getEnabled()) {
            return encryptedText;
        }
        try {
            return aes.decryptStr(encryptedText);
        } catch (Exception e) {
            log.error("Decryption failed", e);
            throw new BusinessException("解密失败", e);
        }
    }

    public String encryptIfNeeded(String value, Boolean isEncrypted) {
        if (Boolean.TRUE.equals(isEncrypted)) {
            return encrypt(value);
        }
        return value;
    }

    public String decryptIfNeeded(String value, Boolean isEncrypted) {
        if (Boolean.TRUE.equals(isEncrypted)) {
            return decrypt(value);
        }
        return value;
    }

    public boolean isEncryptionEnabled() {
        return properties.getEnabled();
    }
}
