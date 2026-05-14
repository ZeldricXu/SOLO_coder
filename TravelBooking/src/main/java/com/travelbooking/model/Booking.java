package com.travelbooking.model;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.Instant;

@Data
@Entity
@Table(name = "bookings")
public class Booking {
    @Id
    @Column(name = "booking_id", length = 50)
    private String bookingId;

    @Column(name = "route_id", length = 50)
    private String routeId;

    @Column(name = "tourist_id", length = 50)
    private String touristId;

    @Column(name = "booking_count")
    private Integer bookingCount;

    @Column(name = "booking_amount", precision = 15, scale = 2)
    private BigDecimal bookingAmount;

    @Column(name = "booking_status", length = 50)
    private String bookingStatus;

    @Column(name = "booking_time")
    private Instant bookingTime;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;
}
