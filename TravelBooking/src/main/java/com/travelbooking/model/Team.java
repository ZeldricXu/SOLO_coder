package com.travelbooking.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;

@Data
@Entity
@Table(name = "teams")
public class Team {
    @Id
    @Column(name = "team_id", length = 50)
    private String teamId;

    @Column(name = "team_name", nullable = false, length = 100)
    private String teamName;

    @Column(name = "team_status", length = 50)
    private String teamStatus;

    @Column(name = "team_capacity")
    private Integer teamCapacity;

    @Column(name = "created_at")
    private Instant createdAt;
}
