package com.web3platform.addressmanagement.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HdWallet {

    private String walletId;

    private String mnemonic;

    private String seedHex;

    private String masterPublicKey;

    private String masterPrivateKey;

    private String chainCode;

    private LocalDateTime createdAt;
}
