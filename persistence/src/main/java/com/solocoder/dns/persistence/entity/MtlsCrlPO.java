package com.solocoder.dns.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("mtls_crl")
public class MtlsCrlPO {
    @TableId(type = IdType.INPUT)
    private String crlId;
    private String serialNumber;
    private String reason;
    private LocalDateTime revokedAt;
    private LocalDateTime expiresAt;
}
