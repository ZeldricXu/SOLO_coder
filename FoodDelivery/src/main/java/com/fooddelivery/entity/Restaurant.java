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
@Table(name = "restaurants")
public class Restaurant {
    @Id
    @Column(name = "restaurant_id")
    private String restaurantId;

    @Column(name = "restaurant_name", nullable = false)
    private String restaurantName;

    @Column(name = "restaurant_type")
    private String restaurantType;

    @Column(name = "restaurant_address")
    private String restaurantAddress;

    @Column(name = "restaurant_region")
    private String restaurantRegion;

    @Column(name = "restaurant_status")
    private String restaurantStatus;

    @Column(name = "restaurant_rating")
    private Double restaurantRating;

    @Column(name = "restaurant_rating_count")
    private Integer restaurantRatingCount;

    @Column(name = "restaurant_order_count")
    private Integer restaurantOrderCount;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (restaurantRating == null) {
            restaurantRating = 0.0;
        }
        if (restaurantRatingCount == null) {
            restaurantRatingCount = 0;
        }
        if (restaurantOrderCount == null) {
            restaurantOrderCount = 0;
        }
    }
}
