package com.web3platform.multisigwallet.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MultisigWallet {

    private String walletAddress;
    private String chainType;
    private List<String> owners;
    private int threshold;
    private long nonce;
}
