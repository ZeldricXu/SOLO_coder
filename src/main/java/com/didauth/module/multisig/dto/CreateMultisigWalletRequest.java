package com.didauth.module.multisig.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class CreateMultisigWalletRequest implements Serializable {

    @NotBlank(message = "chainType不能为空")
    private String chainType;

    @NotNull(message = "threshold不能为空")
    private Integer threshold;

    @NotNull(message = "signers不能为空")
    private List<String> signers;

    private String name;

    private String userId;
}
