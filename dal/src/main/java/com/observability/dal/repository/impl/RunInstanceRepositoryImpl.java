package com.observability.dal.repository.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.observability.common.entity.RunInstanceEntity;
import com.observability.dal.mapper.RunInstanceMapper;
import com.observability.dal.repository.RunInstanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class RunInstanceRepositoryImpl implements RunInstanceRepository {

    private final RunInstanceMapper runInstanceMapper;

    @Override
    public RunInstanceEntity save(RunInstanceEntity entity) {
        runInstanceMapper.insert(entity);
        return entity;
    }

    @Override
    public Optional<RunInstanceEntity> findLatestByEntityId(String entityId) {
        return Optional.ofNullable(
                runInstanceMapper.selectOne(
                        new QueryWrapper<RunInstanceEntity>()
                                .eq("entity_id", entityId)
                                .orderByDesc("created_at")
                                .last("LIMIT 1")
                )
        );
    }
}
