package com.iotplatform.offlinecache.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.Map;

@Data
public class CachePutDTO {

    @NotBlank(message = "缓存键不能为空")
    private String cacheKey;

    @NotBlank(message = "缓存值不能为空")
    private String cacheValue;

    private String dataType;

    private Map<String, Object> metadata;

    private LocalDateTime expireAt;

    private Integer ttlSeconds;
}
