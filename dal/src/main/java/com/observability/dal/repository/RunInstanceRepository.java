package com.observability.dal.repository;

import com.observability.common.entity.RunInstanceEntity;

import java.util.Optional;

public interface RunInstanceRepository {

    RunInstanceEntity save(RunInstanceEntity entity);

    Optional<RunInstanceEntity> findLatestByEntityId(String entityId);
}
