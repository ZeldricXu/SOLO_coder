package com.travelbooking.model;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;

@Data
@Entity
@Table(name = "travel_stats")
public class TravelStat {
    @Id
    @Column(name = "stat_id", length = 50)
    private String statId;

    @Column(name = "stat_month", length = 10)
    private String statMonth;

    @Column(name = "route_count")
    private Integer routeCount;

    @Column(name = "booking_count")
    private Integer bookingCount;

    @Column(name = "tourist_count")
    private Integer touristCount;

    @Column(name = "total_amount", precision = 18, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "departed_count")
    private Integer departedCount;

    @Column(name = "completed_count")
    private Integer completedCount;
}
