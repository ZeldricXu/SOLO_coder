package com.meshcontrol.image.entity;

import com.meshcontrol.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ImageRegistry extends BaseEntity {

    private String registryId;
    private String name;
    private String url;
    private String type;
    private String authType;
    private String username;
    private String passwordEncrypted;
    private Boolean tlsEnabled;
    private Boolean insecureSkipVerify;
    private Integer priority;
    private Boolean enabled;
}
