package com.configcenter.push.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PushTaskEvent {
    
    private String pushId;
    private String configId;
    private String version;
    private String groupId;
    private String pushBy;
    private String pushMessage;
    private LocalDateTime createdAt;
    private int retryCount = 0;
    private int maxRetries = 3;
    private int parallelism;
    
    public static PushTaskEvent create(String pushId, String configId, String version, 
                                       String groupId, String pushBy, int parallelism) {
        return PushTaskEvent.builder()
                .pushId(pushId)
                .configId(configId)
                .version(version)
                .groupId(groupId)
                .pushBy(pushBy)
                .createdAt(LocalDateTime.now())
                .retryCount(0)
                .parallelism(parallelism)
                .build();
    }
    
    public PushTaskEvent forRetry() {
        return PushTaskEvent.builder()
                .pushId(this.pushId)
                .configId(this.configId)
                .version(this.version)
                .groupId(this.groupId)
                .pushBy(this.pushBy)
                .createdAt(this.createdAt)
                .retryCount(this.retryCount + 1)
                .maxRetries(this.maxRetries)
                .parallelism(this.parallelism)
                .build();
    }
    
    public boolean canRetry() {
        return retryCount < maxRetries;
    }
}
