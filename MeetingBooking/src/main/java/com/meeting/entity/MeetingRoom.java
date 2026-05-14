package com.meeting.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "meeting_rooms")
public class MeetingRoom {
    @Id
    @Column(name = "room_id", nullable = false, unique = true)
    private String roomId;

    @Column(name = "room_name", nullable = false)
    private String roomName;

    @Column(name = "room_capacity", nullable = false)
    private Integer roomCapacity;

    @Column(name = "room_location")
    private String roomLocation;

    @Column(name = "room_status", nullable = false)
    private String roomStatus;

    @ElementCollection
    @CollectionTable(name = "room_features", joinColumns = @JoinColumn(name = "room_id"))
    @Column(name = "feature")
    private List<String> roomFeatures = new ArrayList<>();

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
