package com.memberscore.repository;

import com.memberscore.entity.PointRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PointRuleRepository extends JpaRepository<PointRule, Long> {
    
    Optional<PointRule> findByRuleId(String ruleId);
    
    List<PointRule> findByRuleEnabledTrue();
    
    Optional<PointRule> findByRuleTypeAndRuleEnabledTrue(String ruleType);
    
    @Query("SELECT r FROM PointRule r WHERE r.ruleType = :ruleType AND r.ruleEnabled = true " +
           "AND (r.startDate IS NULL OR r.startDate <= :now) " +
           "AND (r.endDate IS NULL OR r.endDate >= :now)")
    Optional<PointRule> findActiveRuleByType(@Param("ruleType") String ruleType, @Param("now") LocalDateTime now);
}
