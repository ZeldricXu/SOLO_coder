package com.contraudit.storage.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.contraudit.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("storage_pin")
public class StoragePin extends BaseEntity {

    private String contentId;

    private String storageType;

    private String requestId;

    private String status;

    private Integer pinCount;

    private String region;

    private LocalDateTime expireAt;

    private String errorMessage;
}
