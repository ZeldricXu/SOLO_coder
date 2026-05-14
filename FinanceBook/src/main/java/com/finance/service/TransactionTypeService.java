package com.finance.service;

import com.finance.entity.TransactionType;
import com.finance.exception.FinanceException;
import com.finance.repository.TransactionTypeRepository;
import com.finance.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionTypeService {

    private final TransactionTypeRepository transactionTypeRepository;

    @Transactional
    public TransactionType createTransactionType(String typeCode, String typeName, String typeDirection,
                                                  Boolean affectsBalance, Boolean requiresCategory,
                                                  String description) {
        if (transactionTypeRepository.existsByTypeCode(typeCode)) {
            throw new FinanceException(400, "收支类型已存在: " + typeCode);
        }

        TransactionType type = TransactionType.builder()
                .typeId(IdGenerator.generateId("ttype"))
                .typeCode(typeCode)
                .typeName(typeName)
                .typeDirection(typeDirection)
                .affectsBalance(affectsBalance)
                .requiresCategory(requiresCategory)
                .typeDescription(description)
                .typeStatus("active")
                .createdAt(LocalDateTime.now())
                .build();

        TransactionType saved = transactionTypeRepository.save(type);
        log.info("创建收支类型成功: typeCode={}, direction={}", typeCode, typeDirection);
        return saved;
    }

    @Transactional(readOnly = true)
    public TransactionType getTransactionTypeByCode(String typeCode) {
        return transactionTypeRepository.findByTypeCode(typeCode)
                .orElseThrow(() -> new FinanceException(404, "收支类型不存在: " + typeCode));
    }

    @Transactional(readOnly = true)
    public List<TransactionType> getAllTransactionTypes() {
        return transactionTypeRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<TransactionType> getActiveTransactionTypes() {
        return transactionTypeRepository.findByTypeStatus("active");
    }

    @Transactional(readOnly = true)
    public List<TransactionType> getTransactionTypesByDirection(String direction) {
        return transactionTypeRepository.findByTypeDirection(direction);
    }

    @Transactional(readOnly = true)
    public boolean isValidTransactionType(String typeCode) {
        return transactionTypeRepository.findByTypeCodeAndTypeStatus(typeCode, "active").isPresent();
    }

    @Transactional(readOnly = true)
    public boolean affectsBalance(String typeCode) {
        Optional<TransactionType> typeOpt = transactionTypeRepository.findByTypeCode(typeCode);
        return typeOpt.map(TransactionType::getAffectsBalance).orElse(true);
    }

    @Transactional(readOnly = true)
    public boolean requiresCategory(String typeCode) {
        Optional<TransactionType> typeOpt = transactionTypeRepository.findByTypeCode(typeCode);
        return typeOpt.map(TransactionType::getRequiresCategory).orElse(true);
    }

    @Transactional(readOnly = true)
    public String getTransactionDirection(String typeCode) {
        Optional<TransactionType> typeOpt = transactionTypeRepository.findByTypeCode(typeCode);
        return typeOpt.map(TransactionType::getTypeDirection).orElse("expense");
    }

    @Transactional(readOnly = true)
    public boolean isIncomeType(String typeCode) {
        return "income".equals(getTransactionDirection(typeCode));
    }

    @Transactional(readOnly = true)
    public boolean isExpenseType(String typeCode) {
        return "expense".equals(getTransactionDirection(typeCode));
    }

    @Transactional
    public TransactionType updateTransactionType(String typeCode, String typeName, String description, String status) {
        TransactionType type = getTransactionTypeByCode(typeCode);

        if (typeName != null) type.setTypeName(typeName);
        if (description != null) type.setTypeDescription(description);
        if (status != null) type.setTypeStatus(status);
        type.setUpdatedAt(LocalDateTime.now());

        return transactionTypeRepository.save(type);
    }

    @Transactional
    public TransactionType activateTransactionType(String typeCode) {
        return updateTransactionType(typeCode, null, null, "active");
    }

    @Transactional
    public TransactionType deactivateTransactionType(String typeCode) {
        return updateTransactionType(typeCode, null, null, "inactive");
    }
}
