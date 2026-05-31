package com.nftindexer.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nft_metadata")
public class NftMetadata extends BaseEntity {

    private String metadataId;
    private String chainId;
    private String contractAddress;
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
    private String status;
    private LocalDateTime mintedAt;
    private LocalDateTime lastUpdatedAt;
    private LocalDateTime indexedAt;
    private String errorDetail;
    private Map<String, Object> metadata;
}
