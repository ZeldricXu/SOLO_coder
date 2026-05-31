package com.datamasker.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("dm_tee_enclave")
public class TeeEnclaveEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String enclaveId;

    private String status;

    private String attestationReport;

    private String measurementHash;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
