package com.memberscore.entity;

import com.memberscore.enums.MemberStatus;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

@Entity
@Table(name = "members")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Member {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "member_id", unique = true, nullable = false)
    private String memberId;
    
    @Column(name = "user_id", unique = true, nullable = false)
    private String userId;
    
    @Column(name = "member_level", nullable = false)
    private String memberLevel;
    
    @Column(name = "total_points", nullable = false)
    private Integer totalPoints;
    
    @Column(name = "available_points", nullable = false)
    private Integer availablePoints;
    
    @Column(name = "used_points", nullable = false)
    private Integer usedPoints;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "member_status", nullable = false)
    private MemberStatus memberStatus;
    
    @Column(name = "registered_at", nullable = false)
    private LocalDateTime registeredAt;
    
    @Column(name = "level_updated_at")
    private LocalDateTime levelUpdatedAt;
    
    @PrePersist
    protected void onCreate() {
        if (registeredAt == null) {
            registeredAt = LocalDateTime.now();
        }
        if (totalPoints == null) {
            totalPoints = 0;
        }
        if (availablePoints == null) {
            availablePoints = 0;
        }
        if (usedPoints == null) {
            usedPoints = 0;
        }
        if (memberStatus == null) {
            memberStatus = MemberStatus.ACTIVE;
        }
        if (memberLevel == null) {
            memberLevel = "bronze";
        }
    }
}
