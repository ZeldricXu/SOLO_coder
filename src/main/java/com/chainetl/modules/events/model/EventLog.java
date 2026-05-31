package com.chainetl.modules.events.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.chainetl.common.handler.JsonListTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "event_logs", autoResultMap = true)
public class EventLog {

    @TableId(type = IdType.INPUT)
    private String logId;

    private String chainId;

    private Long blockNumber;

    private String txHash;

    private Integer logIndex;

    private String contractAddress;

    private String eventSignature;

    @TableField(typeHandler = JsonListTypeHandler.class)
    private List<String> topics;

    private String data;

    private Boolean processed;

    private Instant processedAt;
}
