package com.web3platform.addressmanagement.model;

import lombok.Data;

@Data
public class HdWalletCreateRequest {

    private String mnemonic;

    private String password;

    private String chainType;

    private int accountIndex;

    private int addressCount;
}
