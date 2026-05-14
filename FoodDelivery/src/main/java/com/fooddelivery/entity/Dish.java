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
@Table(name = "dishes")
public class Dish {
    @Id
    @Column(name = "dish_id")
    private String dishId;

    @Column(name = "restaurant_id", nullable = false)
    private String restaurantId;

    @Column(name = "dish_name", nullable = false)
    private String dishName;

    @Column(name = "dish_price", nullable = false)
    private Double dishPrice;

    @Column(name = "dish_desc")
    private String dishDesc;

    @Column(name = "dish_status")
    private String dishStatus;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (dishStatus == null) {
            dishStatus = "active";
        }
    }
}
