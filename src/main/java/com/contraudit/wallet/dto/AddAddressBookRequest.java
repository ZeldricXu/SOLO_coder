package com.contraudit.wallet.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AddAddressBookRequest {

    @NotBlank(message = "address cannot be blank")
    private String address;

    @NotBlank(message = "chain type cannot be blank")
    private String chainType;

    @NotBlank(message = "label cannot be blank")
    private String label;

    private String description;

    private String category;

    private Integer isWhitelist;

    private Integer isBlacklist;
}
