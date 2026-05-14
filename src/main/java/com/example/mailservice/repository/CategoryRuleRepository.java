package com.example.mailservice.repository;

import com.example.mailservice.model.CategoryRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CategoryRuleRepository extends JpaRepository<CategoryRule, Long> {
    Optional<CategoryRule> findByRuleId(String ruleId);

    List<CategoryRule> findByEnabledTrueOrderByRulePriorityDesc();

    List<CategoryRule> findByEnabledTrueOrderByRulePriorityAsc();

    List<CategoryRule> findByEnabledTrueOrderByDynamicPriorityDesc();

    List<CategoryRule> findByTargetCategory(String targetCategory);

    @Modifying
    @Query("UPDATE CategoryRule r SET r.matchCount = r.matchCount + 1, r.lastMatchedAt = CURRENT_TIMESTAMP WHERE r.ruleId = :ruleId")
    void incrementMatchCount(@Param("ruleId") String ruleId);

    @Modifying
    @Query("UPDATE CategoryRule r SET r.dynamicPriority = :priority WHERE r.ruleId = :ruleId")
    void updateDynamicPriority(@Param("ruleId") String ruleId, @Param("priority") Integer priority);

    @Query("SELECT r.ruleId, r.matchCount, r.rulePriority FROM CategoryRule r WHERE r.enabled = true")
    List<Object[]> findRuleMatchCounts();
}
