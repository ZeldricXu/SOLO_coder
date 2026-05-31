package com.nftindexer.modules.address.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AddressDeriveRequest {

    @NotBlank(message = "助记词或根公钥不能为空")
    private String mnemonic;

    @NotBlank(message = "链ID不能为空")
    private String chainId;

    @NotBlank(message = "派生路径不能为空")
    private String derivationPath;

    private String passphrase;

    private String label;

    private String category;

    private String description;

    private String[] tags;

    private String createdBy;
}
