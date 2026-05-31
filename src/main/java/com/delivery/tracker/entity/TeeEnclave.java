package com.delivery.tracker.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.delivery.tracker.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("tee_enclave")
public class TeeEnclave extends BaseEntity {

    private String enclaveId;

    private String status;

    private String attestationReport;

    private String publicKey;

    private LocalDateTime lastHealthCheck;
}
