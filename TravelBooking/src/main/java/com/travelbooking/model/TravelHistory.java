package com.travelbooking.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;

@Data
@Entity
@Table(name = "travel_history")
public class TravelHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "record_type", length = 50)
    private String recordType;

    @Column(name = "reference_id", length = 50)
    private String referenceId;

    @Column(name = "action", length = 100)
    private String action;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "created_at")
    private Instant createdAt;
}
