package com.houserental.service;

import com.houserental.dto.ContractRenewDTO;
import com.houserental.entity.Contract;
import com.houserental.exception.HouseRentalException;
import com.houserental.repository.ContractRepository;
import com.houserental.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class ContractService {

    @Autowired
    private ContractRepository contractRepository;

    @Autowired
    private HouseService houseService;

    @Autowired
    private RentService rentService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private StatisticsService statisticsService;

    @Transactional
    public Contract createContractFromApplication(String applicationId, String houseId,
                                                   String tenantId, String landlordId,
                                                   double baseRent, LocalDate contractStart,
                                                   LocalDate contractEnd, Double specifiedRent) {
        LocalDate effectiveStart = contractStart != null ? contractStart : LocalDate.now();
        LocalDate effectiveEnd = contractEnd != null ? contractEnd : effectiveStart.plusYears(1);

        if (effectiveEnd.isBefore(effectiveStart)) {
            throw new HouseRentalException(400, "合同结束日期不能早于开始日期");
        }

        double contractRent = specifiedRent != null ? specifiedRent : baseRent;

        Contract contract = new Contract();
        contract.setContractId(IdGenerator.generateContractId());
        contract.setHouseId(houseId);
        contract.setTenantId(tenantId);
        contract.setLandlordId(landlordId);
        contract.setContractStart(effectiveStart);
        contract.setContractEnd(effectiveEnd);
        contract.setContractRent(contractRent);
        contract.setContractStatus("active");
        contract.setRenewalCount(0);
        contract.setSignedAt(LocalDateTime.now());

        Contract saved = contractRepository.save(contract);

        houseService.updateHouseStatus(houseId, "rented");
        statisticsService.incrementContractCount();

        historyService.recordContractHistory(
                saved.getContractId(),
                "CREATE",
                "租赁合同签订成功，租期：" + effectiveStart + " 至 " + effectiveEnd + "，租金：" + contractRent,
                houseId,
                tenantId,
                landlordId
        );

        rentService.createPendingPayment(saved);

        return saved;
    }

    @Transactional
    public Contract getContractById(String contractId) {
        return contractRepository.findByContractId(contractId)
                .orElseThrow(() -> new HouseRentalException(404, "合同不存在: " + contractId));
    }

    @Transactional
    public Contract renewContract(ContractRenewDTO dto) {
        Contract oldContract = getContractById(dto.getContractId());

        if (!"active".equals(oldContract.getContractStatus())) {
            throw new HouseRentalException(400, "合同状态异常，无法续签");
        }

        if (dto.getNewContractEnd().isBefore(dto.getNewContractStart())) {
            throw new HouseRentalException(400, "续签合同结束日期不能早于开始日期");
        }

        double newRent = dto.getNewRent() != null ? dto.getNewRent() : oldContract.getContractRent();

        oldContract.setContractStatus("renewed");
        contractRepository.save(oldContract);

        Contract newContract = new Contract();
        newContract.setContractId(IdGenerator.generateContractId());
        newContract.setHouseId(oldContract.getHouseId());
        newContract.setTenantId(oldContract.getTenantId());
        newContract.setLandlordId(oldContract.getLandlordId());
        newContract.setContractStart(dto.getNewContractStart());
        newContract.setContractEnd(dto.getNewContractEnd());
        newContract.setContractRent(newRent);
        newContract.setContractStatus("active");
        newContract.setRenewalCount(oldContract.getRenewalCount() + 1);
        newContract.setPreviousContractId(oldContract.getContractId());
        newContract.setSignedAt(LocalDateTime.now());

        Contract saved = contractRepository.save(newContract);

        statisticsService.incrementRenewalCount();

        historyService.recordContractHistory(
                saved.getContractId(),
                "RENEW",
                "合同续签成功，原合同ID：" + oldContract.getContractId() +
                        "，新租期：" + dto.getNewContractStart() + " 至 " + dto.getNewContractEnd() +
                        "，新租金：" + newRent,
                oldContract.getHouseId(),
                oldContract.getTenantId(),
                oldContract.getLandlordId()
        );

        rentService.createPendingPayment(saved);

        return saved;
    }

    @Transactional
    public Contract terminateContract(String contractId, String reason) {
        Contract contract = getContractById(contractId);

        if (!"active".equals(contract.getContractStatus())) {
            throw new HouseRentalException(400, "合同状态异常，无法终止");
        }

        contract.setContractStatus("terminated");
        Contract saved = contractRepository.save(contract);

        houseService.updateHouseStatus(contract.getHouseId(), "available");

        historyService.recordContractHistory(
                saved.getContractId(),
                "TERMINATE",
                "租赁合同终止，原因：" + (reason != null ? reason : "无"),
                contract.getHouseId(),
                contract.getTenantId(),
                contract.getLandlordId()
        );

        return saved;
    }

    @Transactional
    public Contract expireContract(String contractId) {
        Contract contract = getContractById(contractId);

        if (!"active".equals(contract.getContractStatus())) {
            throw new HouseRentalException(400, "合同状态异常");
        }

        contract.setContractStatus("expired");
        Contract saved = contractRepository.save(contract);

        houseService.updateHouseStatus(contract.getHouseId(), "available");

        historyService.recordContractHistory(
                saved.getContractId(),
                "EXPIRE",
                "租赁合同到期",
                contract.getHouseId(),
                contract.getTenantId(),
                contract.getLandlordId()
        );

        return saved;
    }

    public List<Contract> getAllContracts() {
        return contractRepository.findAll();
    }

    public List<Contract> getActiveContracts() {
        return contractRepository.findByContractStatus("active");
    }

    public List<Contract> getExpiredContracts() {
        return contractRepository.findByContractStatus("expired");
    }

    public List<Contract> getTerminatedContracts() {
        return contractRepository.findByContractStatus("terminated");
    }

    public List<Contract> getContractsByHouse(String houseId) {
        return contractRepository.findByHouseId(houseId);
    }

    public List<Contract> getContractsByTenant(String tenantId) {
        return contractRepository.findByTenantId(tenantId);
    }

    public List<Contract> getContractsByLandlord(String landlordId) {
        return contractRepository.findByLandlordId(landlordId);
    }

    public List<Contract> getActiveContractsByHouse(String houseId) {
        return contractRepository.findByHouseIdAndContractStatus(houseId, "active");
    }

    public List<Contract> getActiveContractsByTenant(String tenantId) {
        return contractRepository.findByTenantIdAndContractStatus(tenantId, "active");
    }

    public List<Contract> getActiveContractsByLandlord(String landlordId) {
        return contractRepository.findByLandlordIdAndContractStatus(landlordId, "active");
    }

    public List<Contract> getExpiringContracts(int daysBefore) {
        LocalDate expiringDate = LocalDate.now().plusDays(daysBefore);
        return contractRepository.findExpiringContracts(expiringDate);
    }

    public long countTotalContracts() {
        return contractRepository.countTotalContracts();
    }

    public long countActiveContracts() {
        return contractRepository.countByStatus("active");
    }

    public long countExpiredContracts() {
        return contractRepository.countByStatus("expired");
    }

    public long countRenewedContracts() {
        return contractRepository.countRenewedContracts();
    }
}
