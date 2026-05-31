package com.datamasker.infrastructure.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "datamasker.tee")
public class TeeConfig {

    private String enclavePath = "/opt/enclave";

    private String attestationUrl = "https://attestation.example.com";
}
