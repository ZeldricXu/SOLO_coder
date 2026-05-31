package com.contraudit.wallet.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DeriveAddressRequest {

    @NotBlank(message = "wallet id cannot be blank")
    private String walletId;

    @NotNull(message = "address index cannot be null")
    private Integer addressIndex;

    private String label;
}
