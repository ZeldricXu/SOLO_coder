package com.cdcsync.streamquery.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.cdcsync.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Set;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cdc_stream_query")
public class StreamQuery extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    private static final Set<String> VALID_STATUSES = Set.of(
            "DRAFT", "PARSED", "OPTIMIZED", "GENERATED",
            "EXECUTING", "EXECUTED", "FAILED"
    );

    private static final Set<String> CAN_PARSE_FROM = Set.of("DRAFT", "FAILED");
    private static final Set<String> CAN_OPTIMIZE_FROM = Set.of("PARSED");
    private static final Set<String> CAN_GENERATE_FROM = Set.of("PARSED", "OPTIMIZED");
    private static final Set<String> CAN_EXECUTE_FROM = Set.of("GENERATED", "EXECUTED", "FAILED");

    private String name;

    private String sqlText;

    private String parsedPlanJson;

    private String optimizedPlanJson;

    private String physicalPlanJson;

    private String status;

    private String executionConfig;

    private LocalDateTime lastExecutedAt;

    private Integer executionCount;

    @Version
    private Integer version;

    public boolean canTransitionTo(String newStatus) {
        if (this.status == null) {
            return true;
        }
        return switch (newStatus) {
            case "PARSED" -> CAN_PARSE_FROM.contains(this.status);
            case "OPTIMIZED" -> CAN_OPTIMIZE_FROM.contains(this.status);
            case "GENERATED" -> CAN_GENERATE_FROM.contains(this.status);
            case "EXECUTING" -> CAN_EXECUTE_FROM.contains(this.status);
            case "EXECUTED", "FAILED" -> true;
            default -> false;
        };
    }

    public static boolean isValidStatus(String status) {
        return VALID_STATUSES.contains(status);
    }
}
