package com.device.platform.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.Instant;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("firmware")
public class Firmware extends BaseEntity {
    private String firmwareId;
    private String productKey;
    private String version;
    private String fileName;
    private String fileUrl;
    private Long fileSize;
    private String md5;
    private String signature;
    private String diffFromVersion;
    private Long diffFileSize;
    private String diffFileUrl;
    private String description;
    private String releaseNotes;
    private boolean active;
    private Instant releasedAt;
}
