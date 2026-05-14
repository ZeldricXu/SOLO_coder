package com.eventticket.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "participants")
public class Participant {
    @Id
    @Column(name = "participant_id", length = 50)
    private String participantId;

    @Column(name = "participant_name", length = 100, nullable = false)
    private String participantName;

    @Column(name = "participant_phone", length = 20, nullable = false)
    private String participantPhone;

    @Column(name = "participant_id_type", length = 50)
    private String participantIdType;

    @Column(name = "participant_id_number", length = 100)
    private String participantIdNumber;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
