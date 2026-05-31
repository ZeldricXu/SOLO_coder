package com.nftindexer.modules.indexer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.Map;

@Data
public class NftMetadataIndexRequest {

    @NotBlank(message = "链ID不能为空")
    private String chainId;

    @NotBlank(message = "合约地址不能为空")
    private String contractAddress;

    @NotNull(message = "Token ID不能为空")
    private BigInteger tokenId;

    private String tokenUri;

    private String name;

    private String description;

    private String image;

    private String animationUrl;

    private String externalUrl;

    private Map<String, Object> attributes;

    private Map<String, Object> properties;

    private String rawMetadata;

    private String metadataHash;

    private String standard;

    private String owner;

    private String creator;

    private String minter;

    private BigInteger supply;

    private LocalDateTime mintedAt;

    private LocalDateTime lastUpdatedAt;

    private String errorDetail;

    private Map<String, Object> metadata;
}
