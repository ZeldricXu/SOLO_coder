package com.observability.dal.repository;

import com.observability.common.entity.MetricSnapshotEntity;

public interface MetricSnapshotRepository {

    MetricSnapshotEntity save(MetricSnapshotEntity entity);
}
