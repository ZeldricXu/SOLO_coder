package com.fooddelivery.entity;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import javax.persistence.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "riders")
public class Rider {
    @Id
    @Column(name = "rider_id")
    private String riderId;

    @Column(name = "rider_name", nullable = false)
    private String riderName;

    @Column(name = "rider_phone")
    private String riderPhone;

    @Column(name = "rider_region")
    private String riderRegion;

    @Column(name = "rider_status")
    private String riderStatus;

    @Column(name = "rider_rating")
    private Double riderRating;

    @Column(name = "rider_rating_count")
    private Integer riderRatingCount;

    @Column(name = "rider_count")
    private Integer riderCount;

    @Column(name = "rider_current_order")
    private String riderCurrentOrder;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (riderRating == null) {
            riderRating = 0.0;
        }
        if (riderRatingCount == null) {
            riderRatingCount = 0;
        }
        if (riderCount == null) {
            riderCount = 0;
        }
        if (riderStatus == null) {
            riderStatus = "available";
        }
    }
}
