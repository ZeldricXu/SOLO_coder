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
@TableName("gw_gray_release_rule")
public class GrayReleaseRule {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String routeId;

    private String grayVersion;

    private Integer grayWeight;

    private String grayHeaders;

    private String grayParams;

    private Boolean enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
