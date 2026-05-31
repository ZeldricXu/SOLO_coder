package com.delivery.tracker.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.delivery.tracker.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("stored_file")
public class StoredFile extends BaseEntity {

    private String fileId;

    private String originalName;

    private String storedPath;

    private Long fileSize;

    private String contentType;

    private String lifecyclePolicy;

    private LocalDateTime expireAt;
}
