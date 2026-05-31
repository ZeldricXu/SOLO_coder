package com.observability.dal.repository.impl;

import com.observability.common.entity.MetricSnapshotEntity;
import com.observability.dal.mapper.MetricSnapshotMapper;
import com.observability.dal.repository.MetricSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MetricSnapshotRepositoryImpl implements MetricSnapshotRepository {

    private final MetricSnapshotMapper metricSnapshotMapper;

    @Override
    public MetricSnapshotEntity save(MetricSnapshotEntity entity) {
        metricSnapshotMapper.insert(entity);
        return entity;
    }
}
