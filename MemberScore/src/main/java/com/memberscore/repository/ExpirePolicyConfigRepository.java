package com.memberscore.repository;

import com.memberscore.entity.ExpirePolicyConfig;
import com.memberscore.enums.ExpirePolicyType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ExpirePolicyConfigRepository extends JpaRepository<ExpirePolicyConfig, Long> {
    
    Optional<ExpirePolicyConfig> findByPolicyId(String policyId);
    
    Optional<ExpirePolicyConfig> findByIsDefaultTrueAndIsEnabledTrue();
    
    List<ExpirePolicyConfig> findByPolicyTypeAndIsEnabledTrue(ExpirePolicyType policyType);
    
    List<ExpirePolicyConfig> findByIsEnabledTrue();
    
    Optional<ExpirePolicyConfig> findByPolicyIdAndIsEnabledTrue(String policyId);
}
