package com.hotelbooking.model;

import jakarta.persistence.*;

@Entity
@Table(name = "hotel_stats")
public class HotelStat {
    @Id
    @Column(name = "stat_id", length = 50)
    private String statId;

    @Column(name = "hotel_id", length = 50)
    private String hotelId;

    @Column(name = "stat_month", length = 7)
    private String statMonth;

    @Column(name = "checkin_count")
    private Integer checkinCount;

    @Column(name = "booking_count")
    private Integer bookingCount;

    @Column(name = "total_amount")
    private Double totalAmount;

    @Column(name = "occupancy_rate")
    private Double occupancyRate;

    public HotelStat() {}

    public String getStatId() { return statId; }
    public void setStatId(String statId) { this.statId = statId; }
    public String getHotelId() { return hotelId; }
    public void setHotelId(String hotelId) { this.hotelId = hotelId; }
    public String getStatMonth() { return statMonth; }
    public void setStatMonth(String statMonth) { this.statMonth = statMonth; }
    public Integer getCheckinCount() { return checkinCount; }
    public void setCheckinCount(Integer checkinCount) { this.checkinCount = checkinCount; }
    public Integer getBookingCount() { return bookingCount; }
    public void setBookingCount(Integer bookingCount) { this.bookingCount = bookingCount; }
    public Double getTotalAmount() { return totalAmount; }
    public void setTotalAmount(Double totalAmount) { this.totalAmount = totalAmount; }
    public Double getOccupancyRate() { return occupancyRate; }
    public void setOccupancyRate(Double occupancyRate) { this.occupancyRate = occupancyRate; }
}
