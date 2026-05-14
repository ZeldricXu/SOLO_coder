package com.restaurant.mgmt.repository;

import com.restaurant.mgmt.model.StockWarning;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface StockWarningRepository extends JpaRepository<StockWarning, String> {
    List<StockWarning> findByIngredientId(String ingredientId);
    List<StockWarning> findByHandledFalse();
    List<StockWarning> findByTriggeredAtBetween(LocalDateTime startTime, LocalDateTime endTime);
    List<StockWarning> findByWarningLevel(String warningLevel);
}
