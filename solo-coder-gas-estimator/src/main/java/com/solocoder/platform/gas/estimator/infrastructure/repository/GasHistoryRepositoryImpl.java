package com.solocoder.platform.gas.estimator.infrastructure.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.solocoder.platform.gas.estimator.domain.model.GasHistory;
import com.solocoder.platform.gas.estimator.domain.repository.GasHistoryRepository;
import com.solocoder.platform.persistence.entity.GasHistoryEntity;
import com.solocoder.platform.persistence.mapper.GasHistoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class GasHistoryRepositoryImpl implements GasHistoryRepository {

    private final GasHistoryMapper gasHistoryMapper;

    @Override
    public GasHistory save(GasHistory history) {
        GasHistoryEntity entity = toEntity(history);
        if (entity.getId() == null) {
            gasHistoryMapper.insert(entity);
        } else {
            gasHistoryMapper.updateById(entity);
        }
        return toDomain(entity);
    }

    @Override
    public List<GasHistory> saveAll(List<GasHistory> histories) {
        histories.forEach(this::save);
        return histories;
    }

    @Override
    public Optional<GasHistory> findByChainIdAndBlockNumber(String chainId, Long blockNumber) {
        LambdaQueryWrapper<GasHistoryEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GasHistoryEntity::getChainId, chainId)
                .eq(GasHistoryEntity::getBlockNumber, blockNumber);
        GasHistoryEntity entity = gasHistoryMapper.selectOne(wrapper);
        return Optional.ofNullable(entity).map(this::toDomain);
    }

    @Override
    public List<GasHistory> findRecentByChainId(String chainId, int limit) {
        Page<GasHistoryEntity> page = new Page<>(1, limit);
        LambdaQueryWrapper<GasHistoryEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GasHistoryEntity::getChainId, chainId)
                .orderByDesc(GasHistoryEntity::getBlockNumber);
        return gasHistoryMapper.selectPage(page, wrapper).getRecords().stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<GasHistory> findLatestByChainId(String chainId) {
        LambdaQueryWrapper<GasHistoryEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GasHistoryEntity::getChainId, chainId)
                .orderByDesc(GasHistoryEntity::getBlockNumber)
                .last("LIMIT 1");
        GasHistoryEntity entity = gasHistoryMapper.selectOne(wrapper);
        return Optional.ofNullable(entity).map(this::toDomain);
    }

    private GasHistory toDomain(GasHistoryEntity entity) {
        if (entity == null) {
            return null;
        }
        return GasHistory.builder()
                .id(entity.getId())
                .chainId(entity.getChainId())
                .blockNumber(entity.getBlockNumber())
                .gasPrice(entity.getGasPrice())
                .baseFee(entity.getBaseFee())
                .priorityFee(entity.getPriorityFee())
                .gasUsed(entity.getGasUsed())
                .gasLimit(entity.getGasLimit())
                .transactionCount(entity.getTransactionCount())
                .blockTime(entity.getBlockTime())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    private GasHistoryEntity toEntity(GasHistory domain) {
        if (domain == null) {
            return null;
        }
        GasHistoryEntity entity = new GasHistoryEntity();
        entity.setId(domain.getId());
        entity.setChainId(domain.getChainId());
        entity.setBlockNumber(domain.getBlockNumber());
        entity.setGasPrice(domain.getGasPrice());
        entity.setBaseFee(domain.getBaseFee());
        entity.setPriorityFee(domain.getPriorityFee());
        entity.setGasUsed(domain.getGasUsed());
        entity.setGasLimit(domain.getGasLimit());
        entity.setTransactionCount(domain.getTransactionCount());
        entity.setBlockTime(domain.getBlockTime());
        return entity;
    }
}
