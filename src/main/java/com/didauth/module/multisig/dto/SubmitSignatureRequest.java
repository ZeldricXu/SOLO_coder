package com.didauth.module.multisig.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

@Data
public class SubmitSignatureRequest implements Serializable {

    @NotBlank(message = "proposalId不能为空")
    private String proposalId;

    @NotBlank(message = "signerAddress不能为空")
    private String signerAddress;

    @NotBlank(message = "signature不能为空")
    private String signature;
}
