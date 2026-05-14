package com.configcenter.encryption.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "config-center.encryption")
public class EncryptionProperties {
    
    private String algorithm = "AES";
    private String secretKey = "config-center-secret-key-123456789";
    private String iv = "config-center-iv-";
    private Integer keySize = 256;
    private String cipherAlgorithm = "AES/CBC/PKCS5Padding";
    private Boolean enabled = true;
}
