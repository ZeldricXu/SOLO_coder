package com.crm.repository;

import com.crm.entity.Opportunity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OpportunityRepository extends JpaRepository<Opportunity, String> {
    Optional<Opportunity> findByOpportunityId(String opportunityId);
    List<Opportunity> findByCustomerId(String customerId);
    List<Opportunity> findBySalesId(String salesId);
    List<Opportunity> findByOpportunityStatus(String opportunityStatus);
    
    @Query("SELECT COUNT(o) FROM Opportunity o WHERE MONTH(o.createdAt) = MONTH(CURRENT_DATE) AND YEAR(o.createdAt) = YEAR(CURRENT_DATE)")
    Long countCurrentMonthOpportunities();
    
    @Query("SELECT COUNT(o) FROM Opportunity o WHERE o.opportunityStatus = 'success'")
    Long countSuccessOpportunities();
    
    @Query("SELECT COUNT(o) FROM Opportunity o WHERE o.opportunityStatus = 'failed'")
    Long countFailedOpportunities();
    
    @Query("SELECT SUM(o.opportunityAmount) FROM Opportunity o WHERE o.opportunityStatus = 'success' AND MONTH(o.dealTime) = MONTH(CURRENT_DATE) AND YEAR(o.dealTime) = YEAR(CURRENT_DATE)")
    Double sumCurrentMonthDealAmount();
}
