package com.finance.repository;

import com.finance.entity.ValidationRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ValidationRuleRepository extends JpaRepository<ValidationRule, String> {
    List<ValidationRule> findByTransactionTypeCodeAndRuleStatusOrderByRulePriorityAsc(String transactionTypeCode, String status);
    List<ValidationRule> findByRuleStatus(String status);
    Optional<ValidationRule> findByRuleName(String ruleName);
    List<ValidationRule> findByRuleType(String ruleType);
}
