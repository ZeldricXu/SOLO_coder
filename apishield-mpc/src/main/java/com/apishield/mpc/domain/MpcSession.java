package com.apishield.mpc.domain;

import com.apishield.domain.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public class MpcSession extends BaseEntity {
    private String sessionId;
    private String protocolName;
    private SessionStatus status;
    private List<String> participantIds;
    private Map<String, Object> protocolData;
    private Map<String, Object> result;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private int currentRound;
    private int totalRounds;

    public MpcSession() {
        this.participantIds = new ArrayList<>();
        this.protocolData = new HashMap<>();
    }

    public enum SessionStatus {
        CREATED, INITIALIZING, READY, RUNNING, COMPLETED, FAILED, CANCELLED
    }
}
