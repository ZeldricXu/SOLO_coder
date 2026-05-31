package com.edgescheduler.modules.ota.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.edgescheduler.domain.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("firmware_package")
public class FirmwarePackage extends BaseEntity {

    @TableField("firmware_id")
    private String firmwareId;

    @TableField("firmware_name")
    private String firmwareName;

    @TableField("firmware_version")
    private String firmwareVersion;

    @TableField("device_model")
    private String deviceModel;

    @TableField("package_type")
    private String packageType;

    @TableField("package_path")
    private String packagePath;

    @TableField("package_size")
    private Long packageSize;

    @TableField("package_md5")
    private String packageMd5;

    @TableField("delta_from_version")
    private String deltaFromVersion;

    @TableField("delta_package_path")
    private String deltaPackagePath;

    @TableField("delta_package_size")
    private Long deltaPackageSize;

    @TableField(value = "update_params", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> updateParams;

    @TableField("release_notes")
    private String releaseNotes;

    @TableField("release_status")
    private String releaseStatus;

    @TableField("released_at")
    private LocalDateTime releasedAt;
}
