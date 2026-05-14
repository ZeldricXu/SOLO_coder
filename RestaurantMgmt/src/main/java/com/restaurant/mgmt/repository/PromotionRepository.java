package com.restaurant.mgmt.repository;

import com.restaurant.mgmt.model.Promotion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface PromotionRepository extends JpaRepository<Promotion, String> {
    List<Promotion> findByStatus(String status);
    List<Promotion> findByPromotionType(String promotionType);
    List<Promotion> findByStartTimeLessThanEqualAndEndTimeGreaterThanEqual(LocalDateTime time1, LocalDateTime time2);
    List<Promotion> findByStatusAndStartTimeLessThanEqualAndEndTimeGreaterThanEqual(String status, LocalDateTime time1, LocalDateTime time2);
}
