package com.hotelbooking.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "service_records")
public class ServiceRecord {
    @Id
    @Column(name = "service_id", length = 50)
    private String serviceId;

    @Column(name = "room_id", length = 50)
    private String roomId;

    @Column(name = "service_type", length = 50)
    private String serviceType;

    @Column(name = "service_request", length = 255)
    private String serviceRequest;

    @Column(name = "service_status", length = 20)
    private String serviceStatus;

    @Column(name = "service_time")
    private LocalDateTime serviceTime;

    @Column(name = "service_charge")
    private Double serviceCharge;

    public ServiceRecord() {}

    public String getServiceId() { return serviceId; }
    public void setServiceId(String serviceId) { this.serviceId = serviceId; }
    public String getRoomId() { return roomId; }
    public void setRoomId(String roomId) { this.roomId = roomId; }
    public String getServiceType() { return serviceType; }
    public void setServiceType(String serviceType) { this.serviceType = serviceType; }
    public String getServiceRequest() { return serviceRequest; }
    public void setServiceRequest(String serviceRequest) { this.serviceRequest = serviceRequest; }
    public String getServiceStatus() { return serviceStatus; }
    public void setServiceStatus(String serviceStatus) { this.serviceStatus = serviceStatus; }
    public LocalDateTime getServiceTime() { return serviceTime; }
    public void setServiceTime(LocalDateTime serviceTime) { this.serviceTime = serviceTime; }
    public Double getServiceCharge() { return serviceCharge; }
    public void setServiceCharge(Double serviceCharge) { this.serviceCharge = serviceCharge; }
}
