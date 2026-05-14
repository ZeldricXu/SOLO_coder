package com.travelbooking.model;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.Instant;

@Data
@Entity
@Table(name = "guides")
public class Guide {
    @Id
    @Column(name = "guide_id", length = 50)
    private String guideId;

    @Column(name = "guide_name", nullable = false, length = 100)
    private String guideName;

    @Column(name = "guide_phone", length = 50)
    private String guidePhone;

    @Column(name = "guide_rating", precision = 3, scale = 2)
    private BigDecimal guideRating;

    @Column(name = "guide_status", length = 50)
    private String guideStatus;

    @Column(name = "guide_count")
    private Integer guideCount;

    @Column(name = "completed_count")
    private Integer completedCount;

    @Column(name = "created_at")
    private Instant createdAt;
}
