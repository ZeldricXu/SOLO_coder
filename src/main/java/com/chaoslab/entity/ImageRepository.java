package com.chaoslab.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("image_repository")
public class ImageRepository extends BaseEntity {

    private String repoId;
    private String name;
    private String registryUrl;
    private String namespace;
    private String authType;
    private String username;
    private String passwordEncrypted;
    private Boolean tlsVerify;
    private String status;
}
