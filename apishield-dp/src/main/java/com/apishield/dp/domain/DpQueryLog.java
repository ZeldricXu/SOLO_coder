package com.apishield.dp.domain;

import com.apishield.domain.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public class DpQueryLog extends BaseEntity {
    private String logId;
    private String queryId;
    private String userId;
    private String dataSource;
    private String queryType;
    private double epsilon;
    private double delta;
    private double sensitivity;
    private String noiseType;
    private double noiseScale;
    private double originalResult;
    private double noisyResult;
    private LocalDateTime queryTime;
    private boolean budgetExceeded;
    private Map<String, Object> queryParams;
}
