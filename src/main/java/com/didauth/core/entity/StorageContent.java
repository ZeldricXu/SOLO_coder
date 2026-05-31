package com.didauth.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_storage_content")
public class StorageContent extends BaseEntity {

    private String contentId;
    private String storageType;
    private String cid;
    private String contentHash;
    private Long contentSize;
    private String pinStatus;
    private String metadata;
    private String userId;
}
