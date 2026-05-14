package com.houserental.service;

import com.houserental.entity.House;
import com.houserental.entity.Contract;
import com.houserental.repository.ContractRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class StatusService {

    @Autowired
    private HouseService houseService;

    @Autowired
    private ContractRepository contractRepository;

    @Transactional
    public House markHouseAsRented(String houseId) {
        return houseService.updateHouseStatus(houseId, "rented");
    }

    @Transactional
    public House markHouseAsAvailable(String houseId) {
        return houseService.updateHouseStatus(houseId, "available");
    }

    @Transactional
    public House markHouseAsOffline(String houseId) {
        return houseService.updateHouseStatus(houseId, "offline");
    }

    @Transactional
    public House updateHouseStatus(String houseId, String status) {
        return houseService.updateHouseStatus(houseId, status);
    }

    public Map<String, Object> getHouseStatusInfo(String houseId) {
        House house = houseService.getHouseById(houseId);
        Map<String, Object> info = new HashMap<>();
        info.put("houseId", house.getHouseId());
        info.put("houseStatus", house.getHouseStatus());
        info.put("houseAddress", house.getHouseAddress());
        info.put("houseRent", house.getHouseRent());
        info.put("applicationCount", house.getApplicationCount());

        List<Contract> activeContracts = contractRepository.findByHouseIdAndContractStatus(houseId, "active");
        if (!activeContracts.isEmpty()) {
            Contract contract = activeContracts.get(0);
            Map<String, Object> contractInfo = new HashMap<>();
            contractInfo.put("contractId", contract.getContractId());
            contractInfo.put("tenantId", contract.getTenantId());
            contractInfo.put("contractStart", contract.getContractStart());
            contractInfo.put("contractEnd", contract.getContractEnd());
            contractInfo.put("contractRent", contract.getContractRent());
            info.put("activeContract", contractInfo);
        }

        return info;
    }

    public Map<String, Object> getSystemStatusSummary() {
        Map<String, Object> summary = new HashMap<>();
        summary.put("totalHouses", houseService.countTotalHouses());
        summary.put("availableHouses", houseService.countAvailableHouses());
        summary.put("rentedHouses", houseService.countRentedHouses());

        long activeContracts = contractRepository.countByStatus("active");
        long expiredContracts = contractRepository.countByStatus("expired");
        long terminatedContracts = contractRepository.countByStatus("terminated");

        summary.put("activeContracts", activeContracts);
        summary.put("expiredContracts", expiredContracts);
        summary.put("terminatedContracts", terminatedContracts);

        double utilizationRate = houseService.countTotalHouses() > 0
                ? (double) houseService.countRentedHouses() / houseService.countTotalHouses() * 100
                : 0.0;
        summary.put("utilizationRate", String.format("%.2f%%", utilizationRate));

        return summary;
    }

    public boolean isValidHouseStatus(String status) {
        return "available".equals(status) || "rented".equals(status) || "offline".equals(status) || "maintenance".equals(status);
    }

    public boolean isValidApplicationStatus(String status) {
        return "pending".equals(status) || "approved".equals(status) || "rejected".equals(status) || "cancelled".equals(status);
    }

    public boolean isValidContractStatus(String status) {
        return "active".equals(status) || "expired".equals(status) || "terminated".equals(status) || "renewed".equals(status);
    }

    public boolean isValidPaymentStatus(String status) {
        return "pending".equals(status) || "paid".equals(status) || "failed".equals(status) || "refunded".equals(status);
    }
}
