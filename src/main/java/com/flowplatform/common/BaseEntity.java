package com.flowplatform.common;

import java.time.LocalDateTime;

public interface BaseEntity {
    Long getId();
    LocalDateTime getCreateTime();
    LocalDateTime getUpdateTime();
}
