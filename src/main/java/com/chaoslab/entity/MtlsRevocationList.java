package com.chaoslab.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("mtls_revocation_list")
public class MtlsRevocationList extends BaseEntity {

    private String revocationId;
    private String certId;
    private String serialNumber;
    private String reason;
    private LocalDateTime revokedAt;
    private String revokedBy;
    private Integer crlNumber;
}
