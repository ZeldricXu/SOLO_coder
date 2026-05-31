package com.tsdbproxy.vector.index.impl;

import cn.hutool.json.JSONUtil;
import com.tsdbproxy.common.entity.VectorIndex;
import com.tsdbproxy.common.mapper.VectorIndexMapper;
import com.tsdbproxy.vector.index.model.IndexConfig;
import com.tsdbproxy.vector.index.model.IndexStats;
import com.tsdbproxy.vector.index.spi.IndexRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class MybatisIndexRepository implements IndexRepository {

    private final VectorIndexMapper vectorIndexMapper;

    @Override
    public Long create(IndexConfig config) {
        VectorIndex entity = new VectorIndex();
        entity.setName(config.getName());
        entity.setDimension(config.getDimension());
        entity.setMetricType(config.getMetricType());
        entity.setIndexType(config.getIndexType());
        entity.setStatus("building");

        Map<String, Object> params = new HashMap<>();
        params.put("M", config.getM());
        params.put("efConstruction", config.getEfConstruction());
        entity.setIndexParams(JSONUtil.toJsonStr(params));

        vectorIndexMapper.insert(entity);
        return entity.getId();
    }

    @Override
    public IndexStats getStats(Long indexId) {
        VectorIndex entity = vectorIndexMapper.selectById(indexId);
        if (entity == null) {
            return null;
        }
        return IndexStats.builder()
                .indexId(entity.getId())
                .name(entity.getName())
                .totalVectors(entity.getTotalVectors() != null ? entity.getTotalVectors().intValue() : 0)
                .status(entity.getStatus())
                .lastBuildTime(entity.getLastBuildTime())
                .build();
    }

    @Override
    public void updateStatus(Long indexId, String status, int totalVectors) {
        VectorIndex entity = new VectorIndex();
        entity.setId(indexId);
        entity.setStatus(status);
        entity.setTotalVectors((long) totalVectors);
        entity.setLastBuildTime(LocalDateTime.now());
        vectorIndexMapper.updateById(entity);
    }
}
