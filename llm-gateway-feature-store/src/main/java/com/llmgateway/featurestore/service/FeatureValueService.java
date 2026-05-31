package com.llmgateway.featurestore.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.llmgateway.common.api.PageResult;
import com.llmgateway.common.constant.CommonConstants;
import com.llmgateway.common.exception.BusinessException;
import com.llmgateway.featurestore.dto.FeatureIngestDTO;
import com.llmgateway.featurestore.entity.Feature;
import com.llmgateway.featurestore.entity.FeatureValue;
import com.llmgateway.featurestore.mapper.FeatureValueMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeatureValueService {

    private final FeatureValueMapper featureValueMapper;
    private final FeatureService featureService;

    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(value = "featureValue", key = "#dto.featureId + '_' + #dto.entityKey")
    public FeatureValue ingest(FeatureIngestDTO dto) {
        Feature feature = featureService.getById(dto.getFeatureId());

        if (!CommonConstants.STATUS_ACTIVE.equals(feature.getStatus())) {
            throw new BusinessException(
                    String.format("特征未激活，无法写入: featureId=%s, status=%s",
                            dto.getFeatureId(), feature.getStatus())
            );
        }

        FeatureValue featureValue = new FeatureValue();
        featureValue.setFeatureId(dto.getFeatureId());
        featureValue.setEntityKey(dto.getEntityKey());
        featureValue.setValue(dto.getValue());
        featureValue.setTimestampMs(dto.getTimestampMs() != null ? dto.getTimestampMs() : System.currentTimeMillis());
        featureValue.setEventTime(LocalDateTime.now());
        featureValue.setSource(dto.getSource());

        featureValueMapper.insert(featureValue);
        log.debug("特征值写入成功: featureId={}, entityKey={}", dto.getFeatureId(), dto.getEntityKey());
        return featureValue;
    }

    @Cacheable(value = "featureValue", key = "#featureId + '_' + #entityKey")
    public FeatureValue getLatest(String featureId, String entityKey) {
        featureService.getById(featureId);
        return featureValueMapper.selectLatest(featureId, entityKey);
    }

    public List<FeatureValue> getRange(String featureId, String entityKey, LocalDateTime startTime, LocalDateTime endTime) {
        featureService.getById(featureId);
        if (startTime == null || endTime == null) {
            throw new BusinessException("时间范围不能为空");
        }
        if (startTime.isAfter(endTime)) {
            throw new BusinessException("开始时间不能晚于结束时间");
        }
        return featureValueMapper.selectRange(featureId, entityKey, startTime, endTime);
    }

    @Transactional(rollbackFor = Exception.class)
    public void batchIngest(List<FeatureIngestDTO> batch) {
        for (FeatureIngestDTO dto : batch) {
            ingest(dto);
        }
    }
}
