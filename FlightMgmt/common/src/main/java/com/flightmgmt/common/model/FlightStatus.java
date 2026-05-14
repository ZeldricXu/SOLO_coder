package com.flightmgmt.common.model;

import java.time.LocalDateTime;

public class FlightStatus {
    private String statusId;
    private String flightId;
    private String statusType;
    private String statusDetail;
    private LocalDateTime statusTime;

    public FlightStatus() {}

    public String getStatusId() { return statusId; }
    public void setStatusId(String statusId) { this.statusId = statusId; }
    public String getFlightId() { return flightId; }
    public void setFlightId(String flightId) { this.flightId = flightId; }
    public String getStatusType() { return statusType; }
    public void setStatusType(String statusType) { this.statusType = statusType; }
    public String getStatusDetail() { return statusDetail; }
    public void setStatusDetail(String statusDetail) { this.statusDetail = statusDetail; }
    public LocalDateTime getStatusTime() { return statusTime; }
    public void setStatusTime(LocalDateTime statusTime) { this.statusTime = statusTime; }
}
