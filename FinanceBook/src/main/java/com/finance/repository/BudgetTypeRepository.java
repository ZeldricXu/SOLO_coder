package com.finance.repository;

import com.finance.entity.BudgetType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BudgetTypeRepository extends JpaRepository<BudgetType, String> {
    Optional<BudgetType> findByBudgetTypeCode(String budgetTypeCode);
    List<BudgetType> findByTypeStatus(String typeStatus);
    List<BudgetType> findByPriorityLevel(String priorityLevel);
    Optional<BudgetType> findByCategoryPattern(String categoryPattern);
    boolean existsByBudgetTypeCode(String budgetTypeCode);
}
