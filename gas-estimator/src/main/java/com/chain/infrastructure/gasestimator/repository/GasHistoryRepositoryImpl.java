package com.chain.infrastructure.gasestimator.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.chain.infrastructure.persistence.entity.GasHistory;
import com.chain.infrastructure.persistence.mapper.GasHistoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class GasHistoryRepositoryImpl implements GasHistoryRepository {

    private final GasHistoryMapper gasHistoryMapper;

    @Override
    public Mono<List<GasHistory>> findRecentHistory(String chainType, int blocksBack) {
        return Mono.fromCallable(() -> {
            QueryWrapper<GasHistory> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("chain_type", chainType)
                    .orderByDesc("timestamp")
                    .last("LIMIT " + blocksBack);
            return gasHistoryMapper.selectList(queryWrapper);
        });
    }

    @Override
    public Mono<GasHistory> save(GasHistory gasHistory) {
        return Mono.fromCallable(() -> {
            gasHistoryMapper.insert(gasHistory);
            return gasHistory;
        });
    }
}
