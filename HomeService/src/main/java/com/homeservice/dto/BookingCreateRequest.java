package com.homeservice.dto;

import java.time.Instant;

public class BookingCreateRequest {
    private String staffId;
    private String customerId;
    private Instant serviceTime;
    private Integer serviceDuration = 2;

    public BookingCreateRequest() {}

    public String getStaffId() { return staffId; }
    public void setStaffId(String staffId) { this.staffId = staffId; }
    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }
    public Instant getServiceTime() { return serviceTime; }
    public void setServiceTime(Instant serviceTime) { this.serviceTime = serviceTime; }
    public Integer getServiceDuration() { return serviceDuration; }
    public void setServiceDuration(Integer serviceDuration) { this.serviceDuration = serviceDuration; }
}
