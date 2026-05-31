package com.nftindexer.modules.multisig.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class WalletCreateRequest {

    @NotBlank(message = "钱包名称不能为空")
    private String name;

    @NotBlank(message = "链ID不能为空")
    private String chainId;

    @NotNull(message = "签名阈值不能为空")
    private Integer threshold;

    @NotNull(message = "签名者列表不能为空")
    private List<String> signers;

    private String createdBy;

    private Map<String, Object> metadata;
}
