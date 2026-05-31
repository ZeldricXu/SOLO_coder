package com.metricplatform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_api_key")
public class SysApiKey extends BaseEntity {

    @TableId(type = IdType.INPUT)
    private String keyId;

    private String apiKey;

    private String secretKey;

    private String name;

    private List<String> permissions;

    private Integer rateLimitCapacity;

    private String status;

    private LocalDateTime expireAt;
}
