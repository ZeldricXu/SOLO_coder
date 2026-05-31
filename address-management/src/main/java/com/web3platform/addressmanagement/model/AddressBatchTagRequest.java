package com.web3platform.addressmanagement.model;

import lombok.Data;

import java.util.List;

@Data
public class AddressBatchTagRequest {

    private List<String> addresses;

    private String tag;

    private String operation;
}
