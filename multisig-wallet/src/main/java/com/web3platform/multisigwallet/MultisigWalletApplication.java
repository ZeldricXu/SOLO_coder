package com.web3platform.multisigwallet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication(scanBasePackages = {"com.web3platform.multisigwallet", "com.web3platform.persistence", "com.web3platform.chaininteraction"})
public class MultisigWalletApplication {

    public static void main(String[] args) {
        SpringApplication.run(MultisigWalletApplication.class, args);
    }
}
