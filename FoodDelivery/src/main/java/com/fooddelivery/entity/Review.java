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
@Table(name = "reviews")
public class Review {
    @Id
    @Column(name = "review_id")
    private String reviewId;

    @Column(name = "order_id", nullable = false, unique = true)
    private String orderId;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "restaurant_id", nullable = false)
    private String restaurantId;

    @Column(name = "rider_id", nullable = false)
    private String riderId;

    @Column(name = "review_rating", nullable = false)
    private Integer reviewRating;

    @Column(name = "review_content", columnDefinition = "TEXT")
    private String reviewContent;

    @Column(name = "review_time")
    private LocalDateTime reviewTime;

    @PrePersist
    protected void onCreate() {
        reviewTime = LocalDateTime.now();
    }
}
