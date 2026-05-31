package com.llmgateway.featurestore.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.llmgateway.common.api.PageResult;
import com.llmgateway.common.constant.CommonConstants;
import com.llmgateway.common.exception.BusinessException;
import com.llmgateway.common.util.IdGenerator;
import com.llmgateway.featurestore.dto.FeatureRegisterDTO;
import com.llmgateway.featurestore.entity.Feature;
import com.llmgateway.featurestore.mapper.FeatureMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeatureService {

    private final FeatureMapper featureMapper;

    private static final String STATUS_DELETED = "deleted";
    private static final Set<String> VALID_STATUSES = Set.of(
            CommonConstants.STATUS_ACTIVE, CommonConstants.STATUS_INACTIVE, STATUS_DELETED
    );
    private static final Set<String> TERMINAL_STATUSES = Set.of(STATUS_DELETED);

    @Transactional(rollbackFor = Exception.class)
    public Feature register(FeatureRegisterDTO dto) {
        LambdaQueryWrapper<Feature> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Feature::getFeatureName, dto.getFeatureName())
                .eq(Feature::getEntity, dto.getEntity())
                .eq(Feature::getDeleted, 0);
        if (featureMapper.selectCount(wrapper) > 0) {
            throw new BusinessException("特征名称已存在");
        }

        Feature feature = new Feature();
        feature.setFeatureId(IdGenerator.generateFeatureId());
        feature.setFeatureName(dto.getFeatureName());
        feature.setFeatureType(dto.getFeatureType());
        feature.setDescription(dto.getDescription());
        feature.setEntity(dto.getEntity());
        feature.setValueType(dto.getValueType());
        feature.setTtl(dto.getTtl());
        feature.setVersion(1);
        feature.setStatus(CommonConstants.STATUS_ACTIVE);
        feature.setTags(dto.getTags());
        feature.setOwner(dto.getOwner());

        featureMapper.insert(feature);
        log.info("特征注册成功: featureId={}", feature.getFeatureId());
        return feature;
    }

    @Cacheable(value = "feature", key = "#featureId")
    public Feature getById(String featureId) {
        Feature feature = featureMapper.selectById(featureId);
        if (feature == null) {
            throw new BusinessException(404, "特征不存在");
        }
        return feature;
    }

    public PageResult<Feature> list(String entity, String status, Integer pageNum, Integer pageSize) {
        LambdaQueryWrapper<Feature> wrapper = new LambdaQueryWrapper<>();
        if (entity != null) {
            wrapper.eq(Feature::getEntity, entity);
        }
        if (status != null) {
            wrapper.eq(Feature::getStatus, status);
        }
        wrapper.eq(Feature::getDeleted, 0);
        wrapper.orderByDesc(Feature::getCreatedAt);

        IPage<Feature> page = featureMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return PageResult.of(page);
    }

    @CacheEvict(value = "feature", key = "#featureId")
    @Transactional(rollbackFor = Exception.class)
    public Feature updateStatus(String featureId, String status) {
        Feature feature = getById(featureId);

        validateStatusTransition(feature.getStatus(), status);

        feature.setStatus(status);
        feature.setUpdatedAt(LocalDateTime.now());
        featureMapper.updateById(feature);
        log.info("特征状态更新: featureId={}, oldStatus={}, newStatus={}",
                featureId, feature.getStatus(), status);
        return feature;
    }

    @CacheEvict(value = "feature", key = "#featureId")
    @Transactional(rollbackFor = Exception.class)
    public void delete(String featureId) {
        Feature feature = getById(featureId);
        feature.setDeleted(1);
        feature.setStatus(STATUS_DELETED);
        feature.setUpdatedAt(LocalDateTime.now());
        featureMapper.updateById(feature);
        log.info("特征已删除: featureId={}", featureId);
    }

    private void validateStatusTransition(String currentStatus, String newStatus) {
        if (!VALID_STATUSES.contains(newStatus)) {
            throw new BusinessException("无效的目标状态: " + newStatus);
        }

        if (TERMINAL_STATUSES.contains(currentStatus)) {
            throw new BusinessException(
                    String.format("当前状态[%s]为终态，不允许状态转换", currentStatus)
            );
        }
    }
}
