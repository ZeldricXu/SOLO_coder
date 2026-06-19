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
@TableName("gw_route_definition")
public class RouteDefinition {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String routeId;

    private String uri;

    private String predicates;

    private String filters;

    private String metadata;

    private Integer orderNum;

    private Integer status;

    private String matchType;

    private Integer weight;

    private String groupId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
