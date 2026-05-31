package com.solocoder.dns.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("command_log")
public class CommandLogPO {
    @TableId(type = IdType.INPUT)
    private String commandId;
    private String commandType;
    private String aggregateId;
    private String payload;
    private String userId;
    private LocalDateTime issuedAt;
    private String status;
    private String result;
}
