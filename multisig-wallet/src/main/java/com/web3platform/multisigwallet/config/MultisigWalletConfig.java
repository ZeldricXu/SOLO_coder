package com.web3platform.multisigwallet.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "multisig.wallet")
public class MultisigWalletConfig {

    private String defaultChain;
    private boolean autoExecute;
    private long defaultGasLimit;
    private List<String> supportedChains;
    private GnosisSafeConfig gnosisSafe;

    @Data
    public static class GnosisSafeConfig {
        private boolean enabled;
        private Map<String, String> singletonAddress;
    }
}
