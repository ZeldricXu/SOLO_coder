package com.web3platform.addressmanagement.model;

import lombok.Data;

import java.util.List;

@Data
public class AddressBookEntryRequest {

    private String address;

    private String chainType;

    private String label;

    private List<String> tags;

    private String note;
}
