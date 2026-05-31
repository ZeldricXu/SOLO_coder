package com.web3platform.addressmanagement.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AddressResponse {

    private String address;

    private String path;

    private int index;

    private String chainType;

    private String publicKey;
}
