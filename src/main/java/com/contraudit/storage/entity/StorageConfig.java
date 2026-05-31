package com.contraudit.storage.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.contraudit.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("storage_config")
public class StorageConfig extends BaseEntity {

    private String storageType;

    private String configName;

    private String gatewayUrl;

    private String apiKey;

    private String apiSecret;

    private Integer timeout;

    private Integer pinEnabled;

    private Integer defaultPinDuration;

    private Integer status;

    private Integer isDefault;
}
