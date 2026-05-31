package com.contraudit.multisig.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class CreateMultisigWalletRequest {

    @NotBlank(message = "wallet name cannot be blank")
    private String walletName;

    @NotBlank(message = "chain type cannot be blank")
    private String chainType;

    @NotNull(message = "threshold cannot be null")
    private Integer threshold;

    @NotNull(message = "signers cannot be null")
    private List<SignerInfo> signers;

    @Data
    public static class SignerInfo {
        @NotBlank(message = "signer address cannot be blank")
        private String address;
        private String publicKey;
    }
}
