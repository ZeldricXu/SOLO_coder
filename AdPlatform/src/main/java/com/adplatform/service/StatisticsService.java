package com.adplatform.service;

import com.adplatform.dto.EffectEvent;
import com.adplatform.dto.EffectQueryRequest;
import com.adplatform.dto.EffectQueryResponse;
import com.adplatform.entity.AdEffect;
import com.adplatform.exception.BusinessException;
import com.adplatform.repository.AdEffectRepository;
import com.adplatform.repository.AdInfoRepository;
import com.adplatform.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
public class StatisticsService {
    private static final Logger logger = LoggerFactory.getLogger(StatisticsService.class);
    
    private final AdEffectRepository adEffectRepository;
    private final AdInfoRepository adInfoRepository;

    public StatisticsService(AdEffectRepository adEffectRepository,
                           AdInfoRepository adInfoRepository) {
        this.adEffectRepository = adEffectRepository;
        this.adInfoRepository = adInfoRepository;
    }

    @Transactional
    public AdEffect recordExposure(String adId, String position) {
        if (!adInfoRepository.existsById(adId)) {
            throw new BusinessException(404, "广告不存在");
        }

        LocalDate today = LocalDate.now();
        AdEffect effect = getOrCreateEffect(adId, today);
        
        effect.setExposureCount(effect.getExposureCount() + 1);
        updateRates(effect);
        
        adEffectRepository.save(effect);
        logger.debug("曝光记录成功: adId={}, position={}", adId, position);
        return effect;
    }

    @Transactional
    public AdEffect recordClick(String adId, String userInfo) {
        if (!adInfoRepository.existsById(adId)) {
            throw new BusinessException(404, "广告不存在");
        }

        LocalDate today = LocalDate.now();
        AdEffect effect = getOrCreateEffect(adId, today);
        
        effect.setClickCount(effect.getClickCount() + 1);
        updateRates(effect);
        
        adEffectRepository.save(effect);
        logger.debug("点击记录成功: adId={}, userInfo={}", adId, userInfo);
        return effect;
    }

    @Transactional
    public AdEffect recordConversion(String adId) {
        if (!adInfoRepository.existsById(adId)) {
            throw new BusinessException(404, "广告不存在");
        }

        LocalDate today = LocalDate.now();
        AdEffect effect = getOrCreateEffect(adId, today);
        
        effect.setConversionCount(effect.getConversionCount() + 1);
        updateRates(effect);
        
        adEffectRepository.save(effect);
        logger.debug("转化记录成功: adId={}", adId);
        return effect;
    }

    @Transactional
    public void processEffectEvent(EffectEvent event) {
        if ("exposure".equals(event.getEventType())) {
            recordExposure(event.getAdId(), event.getPosition());
        } else if ("click".equals(event.getEventType())) {
            recordClick(event.getAdId(), event.getUserInfo());
        } else if ("conversion".equals(event.getEventType())) {
            recordConversion(event.getAdId());
        }
    }

    public EffectQueryResponse queryEffects(EffectQueryRequest request) {
        if (!adInfoRepository.existsById(request.getAdId())) {
            throw new BusinessException(404, "广告不存在");
        }

        LocalDate startDate = request.getStartDate() != null ? request.getStartDate() : LocalDate.now().minusDays(7);
        LocalDate endDate = request.getEndDate() != null ? request.getEndDate() : LocalDate.now();

        Long totalExposure = adEffectRepository.sumExposureCountByAdIdAndDateRange(
                request.getAdId(), startDate, endDate);
        Long totalClick = adEffectRepository.sumClickCountByAdIdAndDateRange(
                request.getAdId(), startDate, endDate);
        Long totalConversion = adEffectRepository.sumConversionCountByAdIdAndDateRange(
                request.getAdId(), startDate, endDate);

        totalExposure = totalExposure != null ? totalExposure : 0L;
        totalClick = totalClick != null ? totalClick : 0L;
        totalConversion = totalConversion != null ? totalConversion : 0L;

        BigDecimal clickRate = totalExposure > 0 
                ? new BigDecimal(totalClick).divide(new BigDecimal(totalExposure), 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal conversionRate = totalClick > 0 
                ? new BigDecimal(totalConversion).divide(new BigDecimal(totalClick), 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return EffectQueryResponse.builder()
                .exposureCount(totalExposure)
                .clickCount(totalClick)
                .clickRate(clickRate)
                .conversionCount(totalConversion)
                .conversionRate(conversionRate)
                .build();
    }

    public List<AdEffect> getEffectDetails(String adId, LocalDate startDate, LocalDate endDate) {
        return adEffectRepository.findByAdIdAndStatDateBetween(adId, startDate, endDate);
    }

    private AdEffect getOrCreateEffect(String adId, LocalDate statDate) {
        Optional<AdEffect> existing = adEffectRepository.findByAdIdAndStatDate(adId, statDate);
        if (existing.isPresent()) {
            return existing.get();
        }
        
        return AdEffect.builder()
                .effectId(IdGenerator.generateId("effect"))
                .adId(adId)
                .statDate(statDate)
                .exposureCount(0L)
                .clickCount(0L)
                .conversionCount(0L)
                .clickRate(BigDecimal.ZERO)
                .conversionRate(BigDecimal.ZERO)
                .build();
    }

    private void updateRates(AdEffect effect) {
        if (effect.getExposureCount() > 0) {
            effect.setClickRate(new BigDecimal(effect.getClickCount())
                    .divide(new BigDecimal(effect.getExposureCount()), 4, RoundingMode.HALF_UP));
        }
        if (effect.getClickCount() > 0) {
            effect.setConversionRate(new BigDecimal(effect.getConversionCount())
                    .divide(new BigDecimal(effect.getClickCount()), 4, RoundingMode.HALF_UP));
        }
    }
}
