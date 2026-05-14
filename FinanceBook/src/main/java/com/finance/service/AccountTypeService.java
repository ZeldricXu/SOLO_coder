package com.finance.service;

import com.finance.entity.AccountType;
import com.finance.exception.FinanceException;
import com.finance.repository.AccountTypeRepository;
import com.finance.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountTypeService {

    private final AccountTypeRepository accountTypeRepository;

    @Transactional
    public AccountType createType(String typeCode, String typeName, String description) {
        if (accountTypeRepository.findByTypeCode(typeCode).isPresent()) {
            throw new FinanceException(400, "账户类型已存在: " + typeCode);
        }

        AccountType type = AccountType.builder()
                .typeId(IdGenerator.generateTypeId())
                .typeCode(typeCode)
                .typeName(typeName)
                .typeDescription(description)
                .typeStatus("active")
                .createdAt(LocalDateTime.now())
                .build();

        AccountType saved = accountTypeRepository.save(type);
        log.info("创建账户类型成功: typeCode={}", typeCode);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<AccountType> getAllTypes() {
        return accountTypeRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<AccountType> getActiveTypes() {
        return accountTypeRepository.findByTypeStatus("active");
    }

    @Transactional(readOnly = true)
    public AccountType getTypeByCode(String typeCode) {
        return accountTypeRepository.findByTypeCode(typeCode)
                .orElseThrow(() -> new FinanceException(404, "账户类型不存在: " + typeCode));
    }

    @Transactional(readOnly = true)
    public boolean existsByCode(String typeCode) {
        return accountTypeRepository.findByTypeCode(typeCode).isPresent();
    }

    @Transactional
    public AccountType updateType(String typeId, String typeName, String description, String status) {
        AccountType type = accountTypeRepository.findById(typeId)
                .orElseThrow(() -> new FinanceException(404, "账户类型不存在: " + typeId));

        if (typeName != null) type.setTypeName(typeName);
        if (description != null) type.setTypeDescription(description);
        if (status != null) type.setTypeStatus(status);

        return accountTypeRepository.save(type);
    }
}
