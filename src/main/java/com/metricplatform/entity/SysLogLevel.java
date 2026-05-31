package com.metricplatform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_log_level")
public class SysLogLevel extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String loggerName;

    private String level;

    private Boolean effective;

    private LocalDateTime expireAt;

    private String createdBy;
}
