package com.travelbooking.model;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.Instant;

@Data
@Entity
@Table(name = "settlements")
public class Settlement {
    @Id
    @Column(name = "settlement_id", length = 50)
    private String settlementId;

    @Column(name = "booking_id", length = 50)
    private String bookingId;

    @Column(name = "itinerary_id", length = 50)
    private String itineraryId;

    @Column(name = "tourist_id", length = 50)
    private String touristId;

    @Column(name = "settlement_amount", precision = 15, scale = 2)
    private BigDecimal settlementAmount;

    @Column(name = "payment_method", length = 50)
    private String paymentMethod;

    @Column(name = "payment_status", length = 50)
    private String paymentStatus;

    @Column(name = "settlement_status", length = 50)
    private String settlementStatus;

    @Column(name = "settlement_time")
    private Instant settlementTime;
}
