package com.chaoslab.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dns_cache")
public class DnsCache extends BaseEntity {

    private String cacheId;
    private String queryKey;
    private String queryType;
    private Map<String, Object> responseData;
    private Integer ttl;
    private LocalDateTime expiresAt;
    private Integer hitCount;
}
