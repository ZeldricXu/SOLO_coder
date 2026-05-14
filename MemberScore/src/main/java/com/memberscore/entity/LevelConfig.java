package com.memberscore.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

@Entity
@Table(name = "level_configs")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LevelConfig {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "level_id", unique = true, nullable = false)
    private String levelId;
    
    @Column(name = "level_name", nullable = false)
    private String levelName;
    
    @Column(name = "level_points_required", nullable = false)
    private Integer levelPointsRequired;
    
    @Column(name = "level_benefits", columnDefinition = "TEXT")
    private String levelBenefits;
    
    @Column(name = "level_order", nullable = false)
    private Integer levelOrder;
    
    @Column(name = "point_multiplier", nullable = false)
    private Double pointMultiplier;
    
    @Column(name = "is_enabled", nullable = false)
    private Boolean isEnabled;
}
