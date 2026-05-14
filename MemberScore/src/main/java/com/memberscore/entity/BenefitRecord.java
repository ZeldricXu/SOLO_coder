package com.memberscore.entity;

import com.memberscore.enums.BenefitStatus;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

@Entity
@Table(name = "benefit_records")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BenefitRecord {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "benefit_id", unique = true, nullable = false)
    private String benefitId;
    
    @Column(name = "member_id", nullable = false)
    private String memberId;
    
    @Column(name = "level_id")
    private String levelId;
    
    @Column(name = "benefit_type", nullable = false)
    private String benefitType;
    
    @Column(name = "benefit_content", nullable = false)
    private String benefitContent;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "benefit_status", nullable = false)
    private BenefitStatus benefitStatus;
    
    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;
    
    @Column(name = "expire_at")
    private LocalDateTime expireAt;
    
    @PrePersist
    protected void onCreate() {
        if (issuedAt == null) {
            issuedAt = LocalDateTime.now();
        }
        if (benefitStatus == null) {
            benefitStatus = BenefitStatus.ACTIVE;
        }
    }
}
