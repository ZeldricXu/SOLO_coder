package com.nftindexer.modules.address.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

@Data
public class AddressBookRequest {

    @NotBlank(message = "地址不能为空")
    private String address;

    @NotBlank(message = "链ID不能为空")
    private String chainId;

    private String label;

    private String category;

    private String description;

    private String[] tags;

    private String createdBy;

    private Map<String, Object> metadata;
}
