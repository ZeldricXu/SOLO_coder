package com.meshcontrol.mtls.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.meshcontrol.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "ca_bundle", autoResultMap = true)
public class CaBundle extends BaseEntity {

    private String bundleId;
    private String name;
    private String rootCertId;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> intermediateCertIds;

    private Integer rotationDays;
    private Boolean enabled;
}
