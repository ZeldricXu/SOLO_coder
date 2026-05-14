package com.restaurant.mgmt.repository;

import com.restaurant.mgmt.model.StockMovement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {
    List<StockMovement> findByIngredientId(String ingredientId);
    List<StockMovement> findByMovementType(String movementType);
    List<StockMovement> findByCreatedAtBetween(LocalDateTime startTime, LocalDateTime endTime);
    List<StockMovement> findByIngredientIdAndCreatedAtBetween(String ingredientId, LocalDateTime startTime, LocalDateTime endTime);
}
