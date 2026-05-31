package com.streamsql.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.streamsql.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("data_archive_record")
public class DataArchiveRecord extends BaseEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String archiveId;

    private String policyId;

    private String datasourceId;

    private String tableName;

    private String archiveType;

    private String archivePath;

    private Long archiveCount;

    private LocalDate archiveDate;
}
