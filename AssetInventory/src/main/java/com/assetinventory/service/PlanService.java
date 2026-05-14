package com.assetinventory.service;

import com.assetinventory.entity.InventoryPlan;
import com.assetinventory.exception.InventoryException;
import com.assetinventory.repository.InventoryPlanRepository;
import com.assetinventory.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class PlanService {

    private final InventoryPlanRepository planRepository;

    @Autowired
    public PlanService(InventoryPlanRepository planRepository) {
        this.planRepository = planRepository;
    }

    public InventoryPlan createPlan(String planName, String planRange,
                                    LocalDate planStart, LocalDate planEnd) {
        InventoryPlan plan = new InventoryPlan();
        plan.setPlanId(IdGenerator.generatePlanId());
        plan.setPlanName(planName);
        plan.setPlanRange(planRange);
        plan.setPlanStart(planStart);
        plan.setPlanEnd(planEnd);
        plan.setPlanStatus("active");
        plan.setCreatedAt(IdGenerator.now());

        return planRepository.save(plan);
    }

    public List<InventoryPlan> getAllPlans() {
        return planRepository.findAll();
    }

    public List<InventoryPlan> getActivePlans() {
        return planRepository.findByPlanStatus("active");
    }

    public Optional<InventoryPlan> getPlanById(String planId) {
        return planRepository.findByPlanId(planId);
    }

    public InventoryPlan getPlanByIdOrThrow(String planId) {
        return planRepository.findByPlanId(planId)
                .orElseThrow(() -> new InventoryException(404, "盘点计划不存在: " + planId));
    }

    public void validatePlanActive(String planId) {
        InventoryPlan plan = getPlanByIdOrThrow(planId);
        if (!"active".equals(plan.getPlanStatus())) {
            throw new InventoryException(400, "盘点计划已关闭");
        }
    }

    public InventoryPlan updatePlanStatus(String planId, String status) {
        InventoryPlan plan = getPlanByIdOrThrow(planId);
        plan.setPlanStatus(status);
        return planRepository.save(plan);
    }
}
