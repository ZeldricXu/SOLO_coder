package com.hotelbooking.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "settlements")
public class Settlement {
    @Id
    @Column(name = "settlement_id", length = 50)
    private String settlementId;

    @Column(name = "booking_id", length = 50)
    private String bookingId;

    @Column(name = "room_charge")
    private Double roomCharge;

    @Column(name = "service_charge")
    private Double serviceCharge;

    @Column(name = "total_amount")
    private Double totalAmount;

    @Column(name = "settlement_status", length = 20)
    private String settlementStatus;

    @Column(name = "settlement_time")
    private LocalDateTime settlementTime;

    @Column(name = "payment_method", length = 50)
    private String paymentMethod;

    public Settlement() {}

    public String getSettlementId() { return settlementId; }
    public void setSettlementId(String settlementId) { this.settlementId = settlementId; }
    public String getBookingId() { return bookingId; }
    public void setBookingId(String bookingId) { this.bookingId = bookingId; }
    public Double getRoomCharge() { return roomCharge; }
    public void setRoomCharge(Double roomCharge) { this.roomCharge = roomCharge; }
    public Double getServiceCharge() { return serviceCharge; }
    public void setServiceCharge(Double serviceCharge) { this.serviceCharge = serviceCharge; }
    public Double getTotalAmount() { return totalAmount; }
    public void setTotalAmount(Double totalAmount) { this.totalAmount = totalAmount; }
    public String getSettlementStatus() { return settlementStatus; }
    public void setSettlementStatus(String settlementStatus) { this.settlementStatus = settlementStatus; }
    public LocalDateTime getSettlementTime() { return settlementTime; }
    public void setSettlementTime(LocalDateTime settlementTime) { this.settlementTime = settlementTime; }
    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }
}
