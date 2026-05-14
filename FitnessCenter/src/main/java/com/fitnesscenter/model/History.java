package com.fitnesscenter.model;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "history")
public class History {

    @Id
    @Column(name = "history_id")
    private String historyId;

    @Column(name = "member_id")
    private String memberId;

    @Column(name = "action_type", nullable = false)
    private String actionType;

    @Column(name = "action_data", columnDefinition = "TEXT")
    private String actionData;

    @Column(name = "action_time")
    private Instant actionTime;

    @Column(name = "related_id")
    private String relatedId;

    public History() {}

    public String getHistoryId() {
        return historyId;
    }

    public void setHistoryId(String historyId) {
        this.historyId = historyId;
    }

    public String getMemberId() {
        return memberId;
    }

    public void setMemberId(String memberId) {
        this.memberId = memberId;
    }

    public String getActionType() {
        return actionType;
    }

    public void setActionType(String actionType) {
        this.actionType = actionType;
    }

    public String getActionData() {
        return actionData;
    }

    public void setActionData(String actionData) {
        this.actionData = actionData;
    }

    public Instant getActionTime() {
        return actionTime;
    }

    public void setActionTime(Instant actionTime) {
        this.actionTime = actionTime;
    }

    public String getRelatedId() {
        return relatedId;
    }

    public void setRelatedId(String relatedId) {
        this.relatedId = relatedId;
    }
}
