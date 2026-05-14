package com.supplychain.supplier.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.supplychain.common.entity.Supplier;
import com.supplychain.common.entity.SupplierEvaluation;
import com.supplychain.common.enums.SupplierStatus;
import com.supplychain.common.util.IdGenerator;
import com.supplychain.supplier.mapper.SupplierEvaluationMapper;
import com.supplychain.supplier.mapper.SupplierMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SupplierEvaluationService {

    private final SupplierEvaluationMapper evaluationMapper;
    private final SupplierMapper supplierMapper;
    private final SupplierService supplierService;

    @Transactional
    public SupplierEvaluation createEvaluation(SupplierEvaluation evaluation) {
        Supplier supplier = supplierService.getSupplier(evaluation.getSupplierId());
        
        evaluation.setEvaluationId(IdGenerator.generateEvaluationId());
        evaluation.setEvaluationTime(LocalDateTime.now());
        
        double totalScore = calculateTotalScore(evaluation);
        evaluation.setTotalScore(totalScore);
        
        String result = determineEvaluationResult(totalScore);
        evaluation.setEvaluationResult(result);
        
        evaluationMapper.insert(evaluation);
        
        updateSupplierRatingAndStatus(supplier, totalScore);
        
        log.info("供应商评估完成: supplierId={}, score={}, result={}", 
                evaluation.getSupplierId(), totalScore, result);
        return evaluation;
    }

    private double calculateTotalScore(SupplierEvaluation evaluation) {
        double quality = evaluation.getQualityScore() != null ? evaluation.getQualityScore() : 0;
        double delivery = evaluation.getDeliveryScore() != null ? evaluation.getDeliveryScore() : 0;
        double price = evaluation.getPriceScore() != null ? evaluation.getPriceScore() : 0;
        double service = evaluation.getServiceScore() != null ? evaluation.getServiceScore() : 0;
        return (quality + delivery + price + service) / 4.0;
    }

    private String determineEvaluationResult(double totalScore) {
        if (totalScore >= 4.5) return "excellent";
        if (totalScore >= 4.0) return "good";
        if (totalScore >= 3.5) return "qualified";
        if (totalScore >= 3.0) return "need_improvement";
        return "failed";
    }

    private void updateSupplierRatingAndStatus(Supplier supplier, double newScore) {
        List<SupplierEvaluation> evaluations = getEvaluationsBySupplier(supplier.getSupplierId());
        if (evaluations.isEmpty()) {
            supplier.setSupplierRating(newScore);
        } else {
            double avgScore = evaluations.stream()
                    .mapToDouble(e -> e.getTotalScore() != null ? e.getTotalScore() : 0)
                    .average()
                    .orElse(newScore);
            supplier.setSupplierRating(Math.round(avgScore * 10.0) / 10.0);
        }
        
        if (supplier.getSupplierRating() >= 3.5) {
            supplier.setSupplierStatus(SupplierStatus.QUALIFIED.getCode());
        } else if (supplier.getSupplierRating() < 2.5) {
            supplier.setSupplierStatus(SupplierStatus.DISQUALIFIED.getCode());
        }
        
        supplier.setUpdatedAt(LocalDateTime.now());
        supplierMapper.updateById(supplier);
    }

    public List<SupplierEvaluation> getEvaluationsBySupplier(String supplierId) {
        LambdaQueryWrapper<SupplierEvaluation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SupplierEvaluation::getSupplierId, supplierId)
               .orderByDesc(SupplierEvaluation::getEvaluationTime);
        return evaluationMapper.selectList(wrapper);
    }

    public SupplierEvaluation getEvaluation(String evaluationId) {
        return evaluationMapper.selectById(evaluationId);
    }
}
