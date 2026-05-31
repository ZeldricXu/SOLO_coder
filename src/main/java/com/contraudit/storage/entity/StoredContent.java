package com.contraudit.storage.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.contraudit.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("stored_content")
public class StoredContent extends BaseEntity {

    private String contentId;

    private String storageType;

    private String configId;

    private String contentHash;

    private Long contentSize;

    private String mimeType;

    private String metadata;

    private String pinStatus;

    private LocalDateTime pinExpireAt;

    private String accessUrl;
}
