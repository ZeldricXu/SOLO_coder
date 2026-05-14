package com.travelbooking.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;

@Data
@Entity
@Table(name = "spots")
public class Spot {
    @Id
    @Column(name = "spot_id", length = 50)
    private String spotId;

    @Column(name = "spot_name", nullable = false, length = 200)
    private String spotName;

    @Column(name = "spot_location", length = 300)
    private String spotLocation;

    @Column(name = "spot_type", length = 50)
    private String spotType;

    @Column(name = "spot_status", length = 50)
    private String spotStatus;

    @Column(name = "created_at")
    private Instant createdAt;
}
