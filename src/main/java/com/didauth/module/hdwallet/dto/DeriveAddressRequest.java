package com.didauth.module.hdwallet.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class DeriveAddressRequest implements Serializable {

    @NotBlank(message = "chainType不能为空")
    private String chainType;

    private String mnemonic;

    private String derivationPath;

    private Integer index = 0;

    private String label;

    private List<String> tags;

    private String userId;
}
