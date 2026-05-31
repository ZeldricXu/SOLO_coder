package com.contraudit.wallet.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateWalletRequest {

    @NotBlank(message = "wallet name cannot be blank")
    private String walletName;

    @NotBlank(message = "chain type cannot be blank")
    private String chainType;

    private String derivationPath;

    private String mnemonic;

    private String password;
}
