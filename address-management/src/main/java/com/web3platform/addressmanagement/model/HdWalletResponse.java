package com.web3platform.addressmanagement.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HdWalletResponse {

    private String walletId;

    private String mnemonic;

    private String seedHex;

    private String masterPublicKey;

    private String chainCode;
}
