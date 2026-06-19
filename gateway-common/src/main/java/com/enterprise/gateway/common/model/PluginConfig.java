package com.enterprise.gateway.common.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("gw_plugin_config")
public class PluginConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String pluginName;

    private String pluginType;

    private String routeId;

    private String config;

    private Boolean enabled;

    private Integer orderNum;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
