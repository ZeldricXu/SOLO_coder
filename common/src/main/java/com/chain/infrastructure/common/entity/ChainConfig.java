package com.chain.infrastructure.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chain_config")
public class ChainConfig extends BaseEntity {

    private String configId;

    private String namespace;

    private Integer version;

    private String parameters;

    private Boolean enabled;

    private LocalDateTime appliedAt;
}
