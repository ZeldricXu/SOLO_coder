package com.travelbooking.model;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.Instant;

@Data
@Entity
@Table(name = "routes")
public class Route {
    @Id
    @Column(name = "route_id", length = 50)
    private String routeId;

    @Column(name = "route_name", nullable = false, length = 200)
    private String routeName;

    @Column(name = "route_type", length = 50)
    private String routeType;

    @Column(name = "route_duration")
    private Integer routeDuration;

    @Column(name = "route_price", precision = 15, scale = 2)
    private BigDecimal routePrice;

    @Column(name = "route_quota")
    private Integer routeQuota;

    @Column(name = "route_available")
    private Integer routeAvailable;

    @Column(name = "route_status", length = 50)
    private String routeStatus;

    @Column(name = "created_at")
    private Instant createdAt;
}
