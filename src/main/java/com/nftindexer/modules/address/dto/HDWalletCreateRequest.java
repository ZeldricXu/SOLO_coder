package com.nftindexer.modules.address.dto;

import lombok.Data;

@Data
public class HDWalletCreateRequest {

    private String mnemonic;

    private String passphrase;

    private String purpose;

    private String coinType;

    private String createdBy;
}
