package com.adplatform.repository;

import com.adplatform.entity.AdBudget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AdBudgetRepository extends JpaRepository<AdBudget, String> {
    Optional<AdBudget> findByBudgetId(String budgetId);
    List<AdBudget> findByAdId(String adId);
    Optional<AdBudget> findTopByAdIdOrderByCreatedAtDesc(String adId);
    
    @Query("SELECT b FROM AdBudget b WHERE b.adId = :adId AND b.budgetRemaining > 0")
    Optional<AdBudget> findActiveBudgetByAdId(@Param("adId") String adId);
}
