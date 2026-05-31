package com.solocoder.platform.transaction.infrastructure.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.solocoder.platform.persistence.entity.TransactionEntity;
import com.solocoder.platform.persistence.mapper.TransactionMapper;
import com.solocoder.platform.transaction.domain.model.BuiltTransaction;
import com.solocoder.platform.transaction.domain.repository.BuiltTransactionRepository;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class BuiltTransactionRepositoryImpl implements BuiltTransactionRepository {

    private final TransactionMapper transactionMapper;

    @Override
    public BuiltTransaction save(BuiltTransaction transaction) {
        TransactionEntity entity = toEntity(transaction);
        if (entity.getId() == null) {
            transactionMapper.insert(entity);
        } else {
            transactionMapper.updateById(entity);
        }
        return toDomain(entity);
    }

    @Override
    public Optional<BuiltTransaction> findByTxId(String txId) {
        LambdaQueryWrapper<TransactionEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TransactionEntity::getTxId, txId);
        TransactionEntity entity = transactionMapper.selectOne(wrapper);
        return Optional.ofNullable(entity).map(this::toDomain);
    }

    @Override
    public List<BuiltTransaction> findByChainId(String chainId, int limit) {
        Page<TransactionEntity> page = new Page<>(1, limit);
        LambdaQueryWrapper<TransactionEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TransactionEntity::getChainId, chainId)
                .orderByDesc(TransactionEntity::getCreatedAt);
        return transactionMapper.selectPage(page, wrapper).getRecords().stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public List<BuiltTransaction> findByFrom(String from, int limit) {
        Page<TransactionEntity> page = new Page<>(1, limit);
        LambdaQueryWrapper<TransactionEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TransactionEntity::getFromAddress, from)
                .orderByDesc(TransactionEntity::getCreatedAt);
        return transactionMapper.selectPage(page, wrapper).getRecords().stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public List<BuiltTransaction> findByStatus(BuiltTransaction.TransactionStatus status, int limit) {
        Page<TransactionEntity> page = new Page<>(1, limit);
        LambdaQueryWrapper<TransactionEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TransactionEntity::getStatus, status.name())
                .orderByDesc(TransactionEntity::getCreatedAt);
        return transactionMapper.selectPage(page, wrapper).getRecords().stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public boolean updateStatus(String txId, BuiltTransaction.TransactionStatus status) {
        LambdaQueryWrapper<TransactionEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TransactionEntity::getTxId, txId);
        TransactionEntity entity = new TransactionEntity();
        entity.setStatus(status.name());
        return transactionMapper.update(entity, wrapper) > 0;
    }

    @Override
    public boolean deleteByTxId(String txId) {
        LambdaQueryWrapper<TransactionEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TransactionEntity::getTxId, txId);
        return transactionMapper.delete(wrapper) > 0;
    }

    private BuiltTransaction toDomain(TransactionEntity entity) {
        if (entity == null) {
            return null;
        }

        BuiltTransaction.GasSettings gasSettings = null;
        if (entity.getGasLimit() != null) {
            gasSettings = BuiltTransaction.GasSettings.builder()
                    .gasLimit(entity.getGasLimit())
                    .gasPrice(entity.getGasPrice())
                    .maxPriorityFeePerGas(entity.getMaxPriorityFee())
                    .maxFeePerGas(entity.getMaxFee())
                    .gasType(entity.getGasType() != null ? BuiltTransaction.GasSettings.GasType.valueOf(entity.getGasType()) : null)
                    .build();
        }

        BuiltTransaction.MultisigStrategy multisigStrategy = null;
        if (entity.getMultisigType() != null && !"NONE".equals(entity.getMultisigType())) {
            multisigStrategy = BuiltTransaction.MultisigStrategy.builder()
                    .type(BuiltTransaction.MultisigStrategy.MultisigStrategyType.valueOf(entity.getMultisigType()))
                    .threshold(entity.getMultisigThreshold())
                    .owners(entity.getMultisigOwners() != null ? JSON.parseObject(entity.getMultisigOwners(), new TypeReference<List<String>>() {}) : null)
                    .walletAddress(entity.getMultisigWallet())
                    .build();
        }

        List<BuiltTransaction.Signature> signatures = entity.getSignatures() != null ?
                JSON.parseObject(entity.getSignatures(), new TypeReference<List<BuiltTransaction.Signature>>() {}) : null;

        return BuiltTransaction.builder()
                .id(entity.getId())
                .txId(entity.getTxId())
                .chainId(entity.getChainId())
                .from(entity.getFromAddress())
                .to(entity.getToAddress())
                .value(entity.getAmount())
                .data(entity.getData())
                .nonce(entity.getNonce())
                .gasSettings(gasSettings)
                .multisigStrategy(multisigStrategy)
                .status(entity.getStatus() != null ? BuiltTransaction.TransactionStatus.valueOf(entity.getStatus()) : null)
                .unsignedData(entity.getUnsignedData())
                .signedData(entity.getSignedData())
                .signatures(signatures)
                .errorMessage(entity.getErrorMessage())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private TransactionEntity toEntity(BuiltTransaction domain) {
        if (domain == null) {
            return null;
        }
        TransactionEntity entity = new TransactionEntity();
        entity.setId(domain.getId());
        entity.setTxId(domain.getTxId());
        entity.setChainId(domain.getChainId());
        entity.setFromAddress(domain.getFrom());
        entity.setToAddress(domain.getTo());
        entity.setAmount(domain.getValue());
        entity.setData(domain.getData());
        entity.setNonce(domain.getNonce());

        if (domain.getGasSettings() != null) {
            entity.setGasLimit(domain.getGasSettings().getGasLimit());
            entity.setGasPrice(domain.getGasSettings().getGasPrice());
            entity.setMaxPriorityFee(domain.getGasSettings().getMaxPriorityFeePerGas());
            entity.setMaxFee(domain.getGasSettings().getMaxFeePerGas());
            entity.setGasType(domain.getGasSettings().getGasType() != null ? domain.getGasSettings().getGasType().name() : null);
        }

        if (domain.getMultisigStrategy() != null) {
            entity.setMultisigType(domain.getMultisigStrategy().getType() != null ? domain.getMultisigStrategy().getType().name() : "NONE");
            entity.setMultisigThreshold(domain.getMultisigStrategy().getThreshold());
            entity.setMultisigOwners(domain.getMultisigStrategy().getOwners() != null ? JSON.toJSONString(domain.getMultisigStrategy().getOwners()) : null);
            entity.setMultisigWallet(domain.getMultisigStrategy().getWalletAddress());
        } else {
            entity.setMultisigType("NONE");
        }

        entity.setStatus(domain.getStatus() != null ? domain.getStatus().name() : null);
        entity.setUnsignedData(domain.getUnsignedData());
        entity.setSignedData(domain.getSignedData());
        entity.setSignatures(domain.getSignatures() != null ? JSON.toJSONString(domain.getSignatures()) : null);
        entity.setErrorMessage(domain.getErrorMessage());

        return entity;
    }
}
