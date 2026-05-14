package com.authcenter.repository;

import com.authcenter.entity.SecurityPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SecurityPolicyRepository extends JpaRepository<SecurityPolicy, String> {
    
    Optional<SecurityPolicy> findByPolicyType(String policyType);
    
    List<SecurityPolicy> findByPolicyTypeAndEnabledTrueOrderByPriorityDesc(String policyType);
    
    List<SecurityPolicy> findByRoleNameAndEnabledTrueOrderByPriorityDesc(String roleName);
    
    Optional<SecurityPolicy> findByPolicyTypeAndRoleName(String policyType, String roleName);
    
    List<SecurityPolicy> findByPolicyTypeAndRoleNameInAndEnabledTrueOrderByPriorityDesc(
            String policyType, List<String> roleNames);
    
    boolean existsByPolicyTypeAndRoleName(String policyType, String roleName);
    
    List<SecurityPolicy> findByEnabledTrue();
    
    List<SecurityPolicy> findByPolicyTypeInAndRoleNameInAndEnabledTrueOrderByPriorityDesc(
            List<String> policyTypes, List<String> roleNames);
}