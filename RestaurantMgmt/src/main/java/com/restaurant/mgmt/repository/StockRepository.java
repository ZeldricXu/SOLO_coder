package com.restaurant.mgmt.repository;

import com.restaurant.mgmt.model.Stock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StockRepository extends JpaRepository<Stock, String> {
    Optional<Stock> findByIngredientId(String ingredientId);
    List<Stock> findByCategory(String category);
    
    @Query("SELECT s FROM Stock s WHERE s.stockQuantity <= s.warningThreshold")
    List<Stock> findLowStockItems();
    
    boolean existsByIngredientId(String ingredientId);
}
