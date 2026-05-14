package com.supplychain.supplier.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.supplychain.common.entity.Supplier;
import com.supplychain.common.enums.SupplierStatus;
import com.supplychain.common.exception.BusinessException;
import com.supplychain.common.util.IdGenerator;
import com.supplychain.supplier.mapper.SupplierMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SupplierService {

    private final SupplierMapper supplierMapper;

    @Transactional
    public Supplier createSupplier(Supplier supplier) {
        supplier.setSupplierId(IdGenerator.generateSupplierId());
        supplier.setRegisteredAt(LocalDateTime.now());
        supplier.setUpdatedAt(LocalDateTime.now());
        if (supplier.getSupplierStatus() == null) {
            supplier.setSupplierStatus(SupplierStatus.PENDING.getCode());
        }
        if (supplier.getSupplierRating() == null) {
            supplier.setSupplierRating(0.0);
        }
        supplierMapper.insert(supplier);
        log.info("创建供应商成功: {}", supplier.getSupplierId());
        return supplier;
    }

    public Supplier getSupplier(String supplierId) {
        Supplier supplier = supplierMapper.selectById(supplierId);
        if (supplier == null) {
            throw new BusinessException(404, "供应商不存在");
        }
        return supplier;
    }

    public List<Supplier> listSuppliers(String status, String type) {
        LambdaQueryWrapper<Supplier> wrapper = new LambdaQueryWrapper<>();
        if (status != null && !status.isEmpty()) {
            wrapper.eq(Supplier::getSupplierStatus, status);
        }
        if (type != null && !type.isEmpty()) {
            wrapper.eq(Supplier::getSupplierType, type);
        }
        wrapper.orderByDesc(Supplier::getRegisteredAt);
        return supplierMapper.selectList(wrapper);
    }

    @Transactional
    public Supplier updateSupplier(String supplierId, Supplier supplier) {
        Supplier existing = getSupplier(supplierId);
        existing.setSupplierName(supplier.getSupplierName() != null ? supplier.getSupplierName() : existing.getSupplierName());
        existing.setSupplierType(supplier.getSupplierType() != null ? supplier.getSupplierType() : existing.getSupplierType());
        existing.setSupplierContact(supplier.getSupplierContact() != null ? supplier.getSupplierContact() : existing.getSupplierContact());
        existing.setSupplierAddress(supplier.getSupplierAddress() != null ? supplier.getSupplierAddress() : existing.getSupplierAddress());
        existing.setUpdatedAt(LocalDateTime.now());
        supplierMapper.updateById(existing);
        log.info("更新供应商成功: {}", supplierId);
        return existing;
    }

    @Transactional
    public void deleteSupplier(String supplierId) {
        Supplier existing = getSupplier(supplierId);
        existing.setSupplierStatus(SupplierStatus.SUSPENDED.getCode());
        existing.setUpdatedAt(LocalDateTime.now());
        supplierMapper.updateById(existing);
        log.info("停用供应商成功: {}", supplierId);
    }

    public boolean isQualified(String supplierId) {
        Supplier supplier = getSupplier(supplierId);
        return SupplierStatus.QUALIFIED.getCode().equals(supplier.getSupplierStatus());
    }

    public void validateSupplier(String supplierId) {
        if (!isQualified(supplierId)) {
            throw new BusinessException("供应商资质无效，无法进行采购");
        }
    }

    public List<Supplier> findQualifiedSuppliers(String type) {
        LambdaQueryWrapper<Supplier> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Supplier::getSupplierStatus, SupplierStatus.QUALIFIED.getCode());
        if (type != null && !type.isEmpty()) {
            wrapper.eq(Supplier::getSupplierType, type);
        }
        wrapper.orderByDesc(Supplier::getSupplierRating);
        return supplierMapper.selectList(wrapper);
    }

    public Map<String, Object> calculateMatchScore(Supplier supplier, String requiredType, double minRating) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> details = new HashMap<>();

        double typeScore = 0.0;
        if (supplier.getSupplierType() != null && supplier.getSupplierType().equalsIgnoreCase(requiredType)) {
            typeScore = 100.0;
        } else if (supplier.getSupplierType() != null && requiredType != null) {
            typeScore = calculatePartialTypeMatch(supplier.getSupplierType(), requiredType);
        }
        details.put("typeScore", typeScore);

        double ratingScore = 0.0;
        if (supplier.getSupplierRating() != null && minRating > 0) {
            double ratingRatio = supplier.getSupplierRating() / Math.max(minRating, 1.0);
            ratingScore = Math.min(ratingRatio * 100, 100.0);
        } else if (supplier.getSupplierRating() != null) {
            ratingScore = supplier.getSupplierRating() * 20;
        }
        details.put("ratingScore", ratingScore);

        double qualificationScore = 0.0;
        if (SupplierStatus.QUALIFIED.getCode().equals(supplier.getSupplierStatus())) {
            qualificationScore = 100.0;
        } else if (SupplierStatus.PENDING.getCode().equals(supplier.getSupplierStatus())) {
            qualificationScore = 50.0;
        }
        details.put("qualificationScore", qualificationScore);

        double totalScore = (typeScore * 0.4) + (ratingScore * 0.35) + (qualificationScore * 0.25);
        totalScore = BigDecimal.valueOf(totalScore).setScale(2, RoundingMode.HALF_UP).doubleValue();

        String grade = determineMatchGrade(totalScore);

        result.put("totalScore", totalScore);
        result.put("grade", grade);
        result.put("details", details);
        result.put("qualified", totalScore >= 70.0);

        log.info("供应商匹配度计算: supplierId={}, totalScore={}, grade={}",
                supplier.getSupplierId(), totalScore, grade);

        return result;
    }

    private double calculatePartialTypeMatch(String supplierType, String requiredType) {
        String s1 = supplierType.toLowerCase();
        String s2 = requiredType.toLowerCase();
        if (s1.contains(s2) || s2.contains(s1)) {
            return 70.0;
        }
        return 30.0;
    }

    private String determineMatchGrade(double score) {
        if (score >= 90.0) {
            return "A";
        } else if (score >= 80.0) {
            return "B";
        } else if (score >= 70.0) {
            return "C";
        } else if (score >= 60.0) {
            return "D";
        } else {
            return "F";
        }
    }

    @Transactional
    public Supplier updateSupplierRating(String supplierId, double newRating) {
        if (newRating < 0.0 || newRating > 5.0) {
            throw new BusinessException("评分必须在0-5之间");
        }

        Supplier supplier = getSupplier(supplierId);
        double oldRating = supplier.getSupplierRating() != null ? supplier.getSupplierRating() : 0.0;

        supplier.setSupplierRating(newRating);
        supplier.setUpdatedAt(LocalDateTime.now());

        String newGrade = calculateRatingGrade(newRating);
        String oldGrade = calculateRatingGrade(oldRating);

        supplierMapper.updateById(supplier);

        log.info("供应商评级更新: supplierId={}, 旧评级={}({}), 新评级={}({})",
                supplierId, oldGrade, oldRating, newGrade, newRating);

        return supplier;
    }

    public String calculateRatingGrade(double rating) {
        if (rating >= 4.5) {
            return "AAA";
        } else if (rating >= 4.0) {
            return "AA";
        } else if (rating >= 3.5) {
            return "A";
        } else if (rating >= 3.0) {
            return "BBB";
        } else if (rating >= 2.5) {
            return "BB";
        } else if (rating >= 2.0) {
            return "B";
        } else {
            return "C";
        }
    }

    @Transactional
    public Supplier adjustRatingByEvaluation(String supplierId, int qualityScore, int deliveryScore, int serviceScore) {
        validateEvaluationScores(qualityScore, deliveryScore, serviceScore);

        Supplier supplier = getSupplier(supplierId);
        double currentRating = supplier.getSupplierRating() != null ? supplier.getSupplierRating() : 0.0;

        double avgScore = (qualityScore + deliveryScore + serviceScore) / 3.0;
        double normalizedScore = avgScore / 100.0 * 5.0;

        double weight = 0.3;
        double newRating = (currentRating * (1 - weight)) + (normalizedScore * weight);
        newRating = BigDecimal.valueOf(newRating).setScale(2, RoundingMode.HALF_UP).doubleValue();
        newRating = Math.max(0.0, Math.min(5.0, newRating));

        return updateSupplierRating(supplierId, newRating);
    }

    private void validateEvaluationScores(int... scores) {
        for (int score : scores) {
            if (score < 0 || score > 100) {
                throw new BusinessException("评分必须在0-100之间");
            }
        }
    }

    public List<Map<String, Object>> batchMatchSuppliers(List<Supplier> suppliers, String requiredType, double minRating) {
        List<Map<String, Object>> results = new ArrayList<>();

        for (Supplier supplier : suppliers) {
            Map<String, Object> match = calculateMatchScore(supplier, requiredType, minRating);
            match.put("supplierId", supplier.getSupplierId());
            match.put("supplierName", supplier.getSupplierName());
            results.add(match);
        }

        results.sort((a, b) -> {
            double scoreA = (double) a.get("totalScore");
            double scoreB = (double) b.get("totalScore");
            return Double.compare(scoreB, scoreA);
        });

        return results;
    }

    public Map<String, Object> evaluateSupplierQualification(Supplier supplier) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Boolean> checks = new HashMap<>();

        checks.put("hasRequiredStatus", SupplierStatus.QUALIFIED.getCode().equals(supplier.getSupplierStatus()));
        checks.put("hasValidContact", supplier.getSupplierContact() != null && !supplier.getSupplierContact().isEmpty());
        checks.put("hasValidAddress", supplier.getSupplierAddress() != null && !supplier.getSupplierAddress().isEmpty());
        checks.put("hasAcceptableRating", supplier.getSupplierRating() != null && supplier.getSupplierRating() >= 2.0);

        int passedChecks = (int) checks.values().stream().filter(v -> v).count();
        double qualificationScore = (passedChecks / (double) checks.size()) * 100;

        result.put("qualificationScore", qualificationScore);
        result.put("checks", checks);
        result.put("isQualified", qualificationScore >= 75.0);

        return result;
    }
}
