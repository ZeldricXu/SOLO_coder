package com.memberscore.repository;

import com.memberscore.entity.ValidationRule;
import com.memberscore.enums.ValidationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ValidationRuleRepository extends JpaRepository<ValidationRule, Long> {
    
    Optional<ValidationRule> findByRuleId(String ruleId);
    
    Optional<ValidationRule> findBySourceTypeAndIsEnabledTrue(String sourceType);
    
    List<ValidationRule> findByValidationTypeAndIsEnabledTrue(ValidationType validationType);
    
    List<ValidationRule> findByIsEnabledTrue();
    
    boolean existsBySourceType(String sourceType);
}
