package com.chain.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.chain.infrastructure.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("storage_object")
public class StorageObject extends BaseEntity {

    private String objectId;

    private String storageNetwork;

    private String cid;

    private String contentHash;

    private String contentType;

    private Long size;

    private String pinStatus;

    private String pinLocation;

    private String metadata;

    private String originalUrl;
}
