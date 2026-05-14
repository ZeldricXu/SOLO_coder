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
@Table(name = "tracks")
public class Track {
    @Id
    @Column(name = "track_id")
    private String trackId;

    @Column(name = "delivery_id", nullable = false)
    private String deliveryId;

    @Column(name = "track_status")
    private String trackStatus;

    @Column(name = "track_location")
    private String trackLocation;

    @Column(name = "track_time")
    private LocalDateTime trackTime;

    @PrePersist
    protected void onCreate() {
        trackTime = LocalDateTime.now();
    }
}
