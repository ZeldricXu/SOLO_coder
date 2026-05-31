package com.didauth.module.hdwallet.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class DeriveAddressResponse implements Serializable {

    private String walletId;
    private String chainType;
    private String address;
    private String publicKey;
    private String derivationPath;
    private String label;
}
