package com.memberscore.dto;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class BenefitTaskMessage {
    
    private String taskId;
    private String memberId;
    private String levelId;
    private LocalDateTime createdAt;
    private Integer retryCount;
    private String source;
    
    public static BenefitTaskMessage create(String memberId, String levelId, String source) {
        return BenefitTaskMessage.builder()
                .taskId("task_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16))
                .memberId(memberId)
                .levelId(levelId)
                .createdAt(LocalDateTime.now())
                .retryCount(0)
                .source(source)
                .build();
    }
}
