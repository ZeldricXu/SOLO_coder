package com.solocoder.dns.eventstore.model;

import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class Snapshot implements Serializable {
    private String snapshotId;
    private String aggregateId;
    private Integer version;
    private String state;
    private LocalDateTime createdAt;
}
