package com.solocoder.dns.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("domain_event")
public class DomainEventPO {
    @TableId(type = IdType.INPUT)
    private String eventId;
    private String aggregateId;
    private String eventType;
    private String payload;
    private Long sequence;
    private LocalDateTime occurredAt;
    private String metadata;
}
