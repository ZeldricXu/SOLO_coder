package com.web3platform.addressmanagement.model;

import lombok.Data;

@Data
public class AddressDeriveRequest {

    private String walletId;

    private String chainType;

    private String path;

    private int index;

    private int count;
}
