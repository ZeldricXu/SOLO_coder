package com.memberscore.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDate;

@Entity
@Table(name = "point_stats")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PointStat {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "stat_id", unique = true, nullable = false)
    private String statId;
    
    @Column(name = "stat_date", unique = true, nullable = false)
    private LocalDate statDate;
    
    @Column(name = "earn_count", nullable = false)
    private Integer earnCount;
    
    @Column(name = "earn_points", nullable = false)
    private Integer earnPoints;
    
    @Column(name = "consume_count", nullable = false)
    private Integer consumeCount;
    
    @Column(name = "consume_points", nullable = false)
    private Integer consumePoints;
    
    @PrePersist
    protected void onCreate() {
        if (earnCount == null) {
            earnCount = 0;
        }
        if (earnPoints == null) {
            earnPoints = 0;
        }
        if (consumeCount == null) {
            consumeCount = 0;
        }
        if (consumePoints == null) {
            consumePoints = 0;
        }
    }
}
