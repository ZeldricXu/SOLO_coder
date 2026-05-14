package com.memberscore.entity;

import com.memberscore.enums.PointType;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;
import java.time.LocalDate;

@Entity
@Table(name = "point_records")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PointRecord {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "point_id", unique = true, nullable = false)
    private String pointId;
    
    @Column(name = "member_id", nullable = false)
    private String memberId;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "point_type", nullable = false)
    private PointType pointType;
    
    @Column(name = "point_amount", nullable = false)
    private Integer pointAmount;
    
    @Column(name = "point_source")
    private String pointSource;
    
    @Column(name = "consume_type")
    private String consumeType;
    
    @Column(name = "point_balance", nullable = false)
    private Integer pointBalance;
    
    @Column(name = "expire_at")
    private LocalDate expireAt;
    
    @Column(name = "is_expired")
    private Boolean isExpired;
    
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    
    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (isExpired == null) {
            isExpired = false;
        }
    }
}
