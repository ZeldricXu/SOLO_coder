package com.restaurant.mgmt.service;

import com.restaurant.mgmt.exception.BusinessException;
import com.restaurant.mgmt.model.Promotion;
import com.restaurant.mgmt.repository.PromotionRepository;
import com.restaurant.mgmt.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class PromotionService {

    @Autowired
    private PromotionRepository promotionRepository;

    @Autowired
    private HistoryService historyService;

    public Promotion createPromotion(Promotion promotion) {
        if (promotion.getPromotionName() == null || promotion.getPromotionName().trim().isEmpty()) {
            throw new BusinessException("活动名称不能为空");
        }
        
        promotion.setPromotionId(IdGenerator.generatePromotionId());
        promotion.setCreatedAt(LocalDateTime.now());
        promotion.setUpdatedAt(LocalDateTime.now());
        if (promotion.getStatus() == null) {
            promotion.setStatus("draft");
        }
        if (promotion.getUsageCount() == 0) {
            promotion.setUsageCount(0);
        }
        
        Promotion saved = promotionRepository.save(promotion);
        historyService.recordHistory("promotion", saved.getPromotionId(), "创建活动", 
            "创建活动: " + saved.getPromotionName(), "system", "create", "success");
        
        return saved;
    }

    public Promotion updatePromotion(String promotionId, Promotion promotion) {
        Optional<Promotion> existingOpt = promotionRepository.findById(promotionId);
        if (existingOpt.isEmpty()) {
            throw new BusinessException("活动不存在");
        }
        
        Promotion existing = existingOpt.get();
        if ("active".equals(existing.getStatus())) {
            throw new BusinessException("活动已启用，不允许修改");
        }
        
        if (promotion.getPromotionName() != null) {
            existing.setPromotionName(promotion.getPromotionName());
        }
        if (promotion.getPromotionType() != null) {
            existing.setPromotionType(promotion.getPromotionType());
        }
        if (promotion.getDescription() != null) {
            existing.setDescription(promotion.getDescription());
        }
        if (promotion.getDiscountType() != null) {
            existing.setDiscountType(promotion.getDiscountType());
        }
        if (promotion.getDiscountValue() >= 0) {
            existing.setDiscountValue(promotion.getDiscountValue());
        }
        if (promotion.getMinOrderAmount() >= 0) {
            existing.setMinOrderAmount(promotion.getMinOrderAmount());
        }
        if (promotion.getMaxDiscountAmount() >= 0) {
            existing.setMaxDiscountAmount(promotion.getMaxDiscountAmount());
        }
        if (promotion.getApplicableDishes() != null) {
            existing.setApplicableDishes(promotion.getApplicableDishes());
        }
        if (promotion.getStartTime() != null) {
            existing.setStartTime(promotion.getStartTime());
        }
        if (promotion.getEndTime() != null) {
            existing.setEndTime(promotion.getEndTime());
        }
        if (promotion.getUsageLimit() >= 0) {
            existing.setUsageLimit(promotion.getUsageLimit());
        }
        existing.setUpdatedAt(LocalDateTime.now());
        
        return promotionRepository.save(existing);
    }

    public void deletePromotion(String promotionId) {
        Optional<Promotion> promotionOpt = promotionRepository.findById(promotionId);
        if (promotionOpt.isEmpty()) {
            throw new BusinessException("活动不存在");
        }
        if ("active".equals(promotionOpt.get().getStatus())) {
            throw new BusinessException("活动已启用，不允许删除");
        }
        promotionRepository.deleteById(promotionId);
    }

    public Promotion getPromotionById(String promotionId) {
        return promotionRepository.findById(promotionId)
                .orElseThrow(() -> new BusinessException("活动不存在"));
    }

    public List<Promotion> getAllPromotions() {
        return promotionRepository.findAll();
    }

    public List<Promotion> getActivePromotions() {
        LocalDateTime now = LocalDateTime.now();
        return promotionRepository.findByStatusAndStartTimeLessThanEqualAndEndTimeGreaterThanEqual(
            "active", now, now);
    }

    public List<Promotion> getPromotionsByType(String type) {
        return promotionRepository.findByPromotionType(type);
    }

    @Transactional
    public Promotion activatePromotion(String promotionId) {
        Promotion promotion = getPromotionById(promotionId);
        
        if (promotion.getStartTime() == null) {
            promotion.setStartTime(LocalDateTime.now());
        }
        
        promotion.setStatus("active");
        promotion.setUpdatedAt(LocalDateTime.now());
        
        historyService.recordHistory("promotion", promotionId, "启用活动", 
            "启用活动: " + promotion.getPromotionName(), "system", "activate", "success");
        
        return promotionRepository.save(promotion);
    }

    @Transactional
    public Promotion deactivatePromotion(String promotionId) {
        Promotion promotion = getPromotionById(promotionId);
        promotion.setStatus("inactive");
        promotion.setUpdatedAt(LocalDateTime.now());
        
        historyService.recordHistory("promotion", promotionId, "停用活动", 
            "停用活动: " + promotion.getPromotionName(), "system", "deactivate", "success");
        
        return promotionRepository.save(promotion);
    }

    @Transactional
    public void incrementUsage(String promotionId) {
        Promotion promotion = getPromotionById(promotionId);
        promotion.setUsageCount(promotion.getUsageCount() + 1);
        promotion.setUpdatedAt(LocalDateTime.now());
        promotionRepository.save(promotion);
    }

    public double calculateDiscount(String promotionId, double orderAmount, List<String> dishIds) {
        Promotion promotion = getPromotionById(promotionId);
        
        if (!promotion.isActive()) {
            return 0;
        }
        
        if (promotion.getApplicableDishes() != null && !promotion.getApplicableDishes().isEmpty()) {
            boolean hasApplicableDish = dishIds.stream()
                .anyMatch(dishId -> promotion.getApplicableDishes().contains(dishId));
            if (!hasApplicableDish) {
                return 0;
            }
        }
        
        if (orderAmount < promotion.getMinOrderAmount()) {
            return 0;
        }
        
        double discount = 0;
        if ("percentage".equals(promotion.getDiscountType())) {
            discount = orderAmount * (promotion.getDiscountValue() / 100);
        } else if ("fixed".equals(promotion.getDiscountType())) {
            discount = promotion.getDiscountValue();
        }
        
        if (promotion.getMaxDiscountAmount() > 0 && discount > promotion.getMaxDiscountAmount()) {
            discount = promotion.getMaxDiscountAmount();
        }
        
        return discount;
    }
}
