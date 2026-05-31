package com.streamsql.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.streamsql.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cdc_event_record")
public class CdcEventRecord extends BaseEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String eventId;

    private String taskId;

    private String eventType;

    private String schemaName;

    private String tableName;

    private String primaryKeyValue;

    private String beforeData;

    private String afterData;

    private LocalDateTime eventTime;

    private byte[] serializedData;
}
