package com.crm.repository;

import com.crm.entity.Follow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FollowRepository extends JpaRepository<Follow, String> {
    Optional<Follow> findByFollowId(String followId);
    List<Follow> findByCustomerId(String customerId);
    List<Follow> findBySalesId(String salesId);
    
    @Query("SELECT COUNT(f) FROM Follow f WHERE MONTH(f.createdAt) = MONTH(CURRENT_DATE) AND YEAR(f.createdAt) = YEAR(CURRENT_DATE)")
    Long countCurrentMonthFollows();
}
