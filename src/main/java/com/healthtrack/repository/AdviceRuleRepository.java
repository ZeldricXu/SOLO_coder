package com.healthtrack.repository;

import com.healthtrack.entity.AdviceRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AdviceRuleRepository extends JpaRepository<AdviceRule, Long> {
    
    List<AdviceRule> findByEnabledTrueAndIsGlobalTrueOrderByRuleOrderAsc();
    
    List<AdviceRule> findByEnabledTrueAndUserIdOrderByRuleOrderAsc(String userId);
    
    @Query("SELECT r FROM AdviceRule r WHERE r.enabled = true AND (r.isGlobal = true OR r.userId = :userId) ORDER BY r.ruleOrder ASC")
    List<AdviceRule> findApplicableRules(@Param("userId") String userId);
    
    List<AdviceRule> findByEnabledTrueAndIndicatorTypeAndIsGlobalTrueOrderByRuleOrderAsc(String indicatorType);
    
    @Query("SELECT r FROM AdviceRule r WHERE r.enabled = true AND r.indicatorType = :indicatorType AND (r.isGlobal = true OR r.userId = :userId) ORDER BY r.ruleOrder ASC")
    List<AdviceRule> findApplicableRulesByIndicatorType(@Param("userId") String userId, @Param("indicatorType") String indicatorType);
    
    List<AdviceRule> findByUserId(String userId);
    
    List<AdviceRule> findByIsGlobalTrue();
}
