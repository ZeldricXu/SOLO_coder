package com.edgescheduler.ota.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.edgescheduler.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "firmware", autoResultMap = true)
public class Firmware extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private String firmwareId;

    private String firmwareName;

    private String productKey;

    private String version;

    private String filePath;

    private Long fileSize;

    private String md5;

    private String signature;

    private String diffFromVersion;

    private String diffFilePath;

    private String status;

    private String releaseNotes;

    private LocalDateTime publishedAt;

    public interface Status {
        String DRAFT = "draft";
        String PUBLISHED = "published";
        String RETIRED = "retired";
    }
}
