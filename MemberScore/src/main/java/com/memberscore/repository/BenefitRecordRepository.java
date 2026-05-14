package com.memberscore.repository;

import com.memberscore.entity.BenefitRecord;
import com.memberscore.enums.BenefitStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BenefitRecordRepository extends JpaRepository<BenefitRecord, Long> {
    
    Optional<BenefitRecord> findByBenefitId(String benefitId);
    
    List<BenefitRecord> findByMemberIdOrderByIssuedAtDesc(String memberId);
    
    List<BenefitRecord> findByMemberIdAndBenefitStatusOrderByIssuedAtDesc(String memberId, BenefitStatus status);
    
    List<BenefitRecord> findByLevelId(String levelId);
    
    boolean existsByMemberIdAndLevelIdAndBenefitTypeAndBenefitStatus(
            String memberId, String levelId, String benefitType, BenefitStatus status);
}
