package com.datastandard.common.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.time.Instant;

@Getter
public class SloBurnAlertEvent extends ApplicationEvent {

    private final String sloId;
    private final String sloName;
    private final String alertType;
    private final Double burnRate;
    private final Double errorBudgetRemaining;
    private final Long timeWindowSeconds;
    private final Instant alertTime;
    private final String severity;
    private final String traceId;

    public SloBurnAlertEvent(Object source, String sloId, String sloName,
                             String alertType, Double burnRate, Double errorBudgetRemaining,
                             Long timeWindowSeconds, String severity, String traceId) {
        super(source);
        this.sloId = sloId;
        this.sloName = sloName;
        this.alertType = alertType;
        this.burnRate = burnRate;
        this.errorBudgetRemaining = errorBudgetRemaining;
        this.timeWindowSeconds = timeWindowSeconds;
        this.alertTime = Instant.now();
        this.severity = severity;
        this.traceId = traceId;
    }
}
