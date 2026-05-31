package com.didauth.module.hdwallet.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class AddressBookRequest implements Serializable {

    @NotBlank(message = "address不能为空")
    private String address;

    @NotBlank(message = "chainType不能为空")
    private String chainType;

    @NotBlank(message = "name不能为空")
    private String name;

    private String label;

    private List<String> tags;

    private Boolean isWhitelist;

    private Boolean isBlacklist;

    private String userId;
}
