package com.fitnesscenter.repository;

import com.fitnesscenter.model.Plan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlanRepository extends JpaRepository<Plan, String> {
    
    Optional<Plan> findByPlanId(String planId);
    
    Optional<Plan> findByMemberId(String memberId);
    
    List<Plan> findByPlanStatus(String planStatus);
    
    List<Plan> findByPlanType(String planType);
    
    List<Plan> findByMemberIdAndPlanStatus(String memberId, String planStatus);
    
    boolean existsByMemberId(String memberId);
    
    boolean existsByPlanId(String planId);
}
