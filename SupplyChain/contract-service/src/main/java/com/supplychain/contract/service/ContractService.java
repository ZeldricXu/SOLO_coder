package com.supplychain.contract.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.supplychain.common.entity.Contract;
import com.supplychain.common.enums.ContractStatus;
import com.supplychain.common.util.IdGenerator;
import com.supplychain.contract.mapper.ContractMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContractService {

    private final ContractMapper contractMapper;

    @Transactional
    public Contract createContract(Contract contract) {
        contract.setContractId(IdGenerator.generateContractId());
        contract.setContractNo(generateContractNo());
        contract.setCreatedAt(LocalDateTime.now());
        if (contract.getContractStatus() == null) {
            contract.setContractStatus(ContractStatus.DRAFT.getCode());
        }
        if (contract.getContractAmount() == null) {
            contract.setContractAmount(BigDecimal.ZERO);
        }
        contractMapper.insert(contract);
        log.info("创建采购合同: contractId={}, contractNo={}", contract.getContractId(), contract.getContractNo());
        return contract;
    }

    private String generateContractNo() {
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String random = String.format("%04d", (int)(Math.random() * 10000));
        return "PO-" + date + "-" + random;
    }

    public Contract getContract(String contractId) {
        return contractMapper.selectById(contractId);
    }

    public Contract getContractByOrder(String orderId) {
        LambdaQueryWrapper<Contract> wrapper = new LambdaQueryWrapper<>();
        wrapper.apply("JSON_CONTAINS(order_ids, JSON_QUOTE({0}))", orderId)
               .orderByDesc(Contract::getCreatedAt)
               .last("LIMIT 1");
        return contractMapper.selectOne(wrapper);
    }

    public List<Contract> listContracts(String supplierId, String status) {
        LambdaQueryWrapper<Contract> wrapper = new LambdaQueryWrapper<>();
        if (supplierId != null && !supplierId.isEmpty()) {
            wrapper.eq(Contract::getSupplierId, supplierId);
        }
        if (status != null && !status.isEmpty()) {
            wrapper.eq(Contract::getContractStatus, status);
        }
        wrapper.orderByDesc(Contract::getCreatedAt);
        return contractMapper.selectList(wrapper);
    }

    @Transactional
    public Contract updateStatus(String contractId, ContractStatus status) {
        Contract contract = getContract(contractId);
        if (contract != null) {
            contract.setContractStatus(status.getCode());
            if (ContractStatus.SIGNED.getCode().equals(status.getCode())) {
                contract.setSignedAt(LocalDateTime.now());
            }
            contractMapper.updateById(contract);
            log.info("合同状态更新: contractId={}, status={}", contractId, status.getCode());
        }
        return contract;
    }

    @Transactional
    public Contract signContract(String contractId) {
        return updateStatus(contractId, ContractStatus.SIGNED);
    }

    @Transactional
    public Contract generateContractFromOrder(String orderId, String supplierId, BigDecimal amount) {
        Contract contract = Contract.builder()
            .supplierId(supplierId)
            .orderIds(List.of(orderId))
            .contractAmount(amount)
            .contractContent("采购合同 - 订单号: " + orderId)
            .startDate(LocalDateTime.now())
            .endDate(LocalDateTime.now().plusMonths(3))
            .build();
        return createContract(contract);
    }
}
