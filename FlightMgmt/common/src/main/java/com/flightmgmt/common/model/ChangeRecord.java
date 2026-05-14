package com.flightmgmt.common.model;

import java.time.LocalDateTime;

public class ChangeRecord {
    private String changeId;
    private String bookingId;
    private String changeType;
    private String changeReason;
    private double changeAmount;
    private String changeStatus;
    private LocalDateTime changeTime;

    public ChangeRecord() {}

    public String getChangeId() { return changeId; }
    public void setChangeId(String changeId) { this.changeId = changeId; }
    public String getBookingId() { return bookingId; }
    public void setBookingId(String bookingId) { this.bookingId = bookingId; }
    public String getChangeType() { return changeType; }
    public void setChangeType(String changeType) { this.changeType = changeType; }
    public String getChangeReason() { return changeReason; }
    public void setChangeReason(String changeReason) { this.changeReason = changeReason; }
    public double getChangeAmount() { return changeAmount; }
    public void setChangeAmount(double changeAmount) { this.changeAmount = changeAmount; }
    public String getChangeStatus() { return changeStatus; }
    public void setChangeStatus(String changeStatus) { this.changeStatus = changeStatus; }
    public LocalDateTime getChangeTime() { return changeTime; }
    public void setChangeTime(LocalDateTime changeTime) { this.changeTime = changeTime; }
}
