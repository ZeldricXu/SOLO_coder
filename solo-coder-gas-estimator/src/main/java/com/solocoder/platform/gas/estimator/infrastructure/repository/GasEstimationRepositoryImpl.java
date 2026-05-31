package com.solocoder.platform.gas.estimator.infrastructure.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.solocoder.platform.gas.estimator.domain.model.GasEstimation;
import com.solocoder.platform.gas.estimator.domain.repository.GasEstimationRepository;
import com.solocoder.platform.persistence.entity.GasEstimationEntity;
import com.solocoder.platform.persistence.mapper.GasEstimationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class GasEstimationRepositoryImpl implements GasEstimationRepository {

    private final GasEstimationMapper gasEstimationMapper;

    @Override
    public GasEstimation save(GasEstimation estimation) {
        GasEstimationEntity entity = toEntity(estimation);
        if (entity.getId() == null) {
            gasEstimationMapper.insert(entity);
        } else {
            gasEstimationMapper.updateById(entity);
        }
        return toDomain(entity);
    }

    @Override
    public Optional<GasEstimation> findById(Long id) {
        GasEstimationEntity entity = gasEstimationMapper.selectById(id);
        return Optional.ofNullable(entity).map(this::toDomain);
    }

    @Override
    public Optional<GasEstimation> findByEstimationId(String estimationId) {
        LambdaQueryWrapper<GasEstimationEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GasEstimationEntity::getEstimationId, estimationId);
        GasEstimationEntity entity = gasEstimationMapper.selectOne(wrapper);
        return Optional.ofNullable(entity).map(this::toDomain);
    }

    @Override
    public List<GasEstimation> findByChainId(String chainId, int limit) {
        Page<GasEstimationEntity> page = new Page<>(1, limit);
        LambdaQueryWrapper<GasEstimationEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GasEstimationEntity::getChainId, chainId)
                .orderByDesc(GasEstimationEntity::getCreatedAt);
        return gasEstimationMapper.selectPage(page, wrapper).getRecords().stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public List<GasEstimation> findLatest(String chainId, int limit) {
        Page<GasEstimationEntity> page = new Page<>(1, limit);
        LambdaQueryWrapper<GasEstimationEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GasEstimationEntity::getChainId, chainId)
                .orderByDesc(GasEstimationEntity::getTimestamp);
        return gasEstimationMapper.selectPage(page, wrapper).getRecords().stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    private GasEstimation toDomain(GasEstimationEntity entity) {
        if (entity == null) {
            return null;
        }
        return GasEstimation.builder()
                .id(entity.getId())
                .chainId(entity.getChainId())
                .network(entity.getNetwork())
                .gasPrices(GasEstimation.GasPriceLevel.builder()
                        .low(entity.getGasPriceLow())
                        .medium(entity.getGasPriceMedium())
                        .high(entity.getGasPriceHigh())
                        .build())
                .baseFee(entity.getBaseFee())
                .priorityFees(GasEstimation.PriorityFeeLevel.builder()
                        .low(entity.getPriorityFeeLow())
                        .medium(entity.getPriorityFeeMedium())
                        .high(entity.getPriorityFeeHigh())
                        .build())
                .networkStatus(GasEstimation.NetworkStatus.builder()
                        .pendingTransactions(entity.getPendingTransactions())
                        .blockGasUsed(entity.getBlockGasUsed())
                        .blockGasLimit(entity.getBlockGasLimit())
                        .build())
                .latestBlock(entity.getLatestBlock())
                .timestamp(entity.getTimestamp())
                .signature(entity.getSignature())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    private GasEstimationEntity toEntity(GasEstimation domain) {
        if (domain == null) {
            return null;
        }
        GasEstimationEntity entity = new GasEstimationEntity();
        entity.setId(domain.getId());
        entity.setChainId(domain.getChainId());
        entity.setNetwork(domain.getNetwork());
        entity.setGasPriceLow(domain.getGasPrices() != null ? domain.getGasPrices().getLow() : null);
        entity.setGasPriceMedium(domain.getGasPrices() != null ? domain.getGasPrices().getMedium() : null);
        entity.setGasPriceHigh(domain.getGasPrices() != null ? domain.getGasPrices().getHigh() : null);
        entity.setBaseFee(domain.getBaseFee());
        entity.setPriorityFeeLow(domain.getPriorityFees() != null ? domain.getPriorityFees().getLow() : null);
        entity.setPriorityFeeMedium(domain.getPriorityFees() != null ? domain.getPriorityFees().getMedium() : null);
        entity.setPriorityFeeHigh(domain.getPriorityFees() != null ? domain.getPriorityFees().getHigh() : null);
        entity.setPendingTransactions(domain.getNetworkStatus() != null ? domain.getNetworkStatus().getPendingTransactions() : null);
        entity.setBlockGasUsed(domain.getNetworkStatus() != null ? domain.getNetworkStatus().getBlockGasUsed() : null);
        entity.setBlockGasLimit(domain.getNetworkStatus() != null ? domain.getNetworkStatus().getBlockGasLimit() : null);
        entity.setLatestBlock(domain.getLatestBlock());
        entity.setTimestamp(domain.getTimestamp());
        entity.setSignature(domain.getSignature());
        return entity;
    }
}
