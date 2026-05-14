package com.houserental.service;

import com.houserental.entity.History;
import com.houserental.repository.HistoryRepository;
import com.houserental.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class HistoryService {

    @Autowired
    private HistoryRepository historyRepository;

    @Transactional
    public History recordHistory(String historyType, String relatedId, String relatedType,
                                  String action, String description, String houseId,
                                  String tenantId, String landlordId) {
        History history = new History();
        history.setHistoryId(IdGenerator.generateHistoryId());
        history.setHistoryType(historyType);
        history.setRelatedId(relatedId);
        history.setRelatedType(relatedType);
        history.setAction(action);
        history.setDescription(description);
        history.setHouseId(houseId);
        history.setTenantId(tenantId);
        history.setLandlordId(landlordId);
        history.setCreatedAt(LocalDateTime.now());
        return historyRepository.save(history);
    }

    @Transactional
    public History recordApplicationHistory(String applicationId, String action, String description,
                                             String houseId, String tenantId, String landlordId) {
        return recordHistory("application", applicationId, "application",
                action, description, houseId, tenantId, landlordId);
    }

    @Transactional
    public History recordContractHistory(String contractId, String action, String description,
                                          String houseId, String tenantId, String landlordId) {
        return recordHistory("contract", contractId, "contract",
                action, description, houseId, tenantId, landlordId);
    }

    @Transactional
    public History recordPaymentHistory(String paymentId, String action, String description,
                                         String houseId, String tenantId, String landlordId) {
        return recordHistory("payment", paymentId, "payment",
                action, description, houseId, tenantId, landlordId);
    }

    @Transactional
    public History recordHouseHistory(String houseId, String action, String description,
                                       String landlordId) {
        return recordHistory("house", houseId, "house",
                action, description, houseId, null, landlordId);
    }

    @Transactional
    public History recordTenantHistory(String tenantId, String action, String description) {
        return recordHistory("tenant", tenantId, "tenant",
                action, description, null, tenantId, null);
    }

    @Transactional
    public History recordLandlordHistory(String landlordId, String action, String description) {
        return recordHistory("landlord", landlordId, "landlord",
                action, description, null, null, landlordId);
    }

    public List<History> getHouseHistory(String houseId) {
        return historyRepository.findHouseHistory(houseId);
    }

    public List<History> getTenantHistory(String tenantId) {
        return historyRepository.findTenantHistory(tenantId);
    }

    public List<History> getLandlordHistory(String landlordId) {
        return historyRepository.findLandlordHistory(landlordId);
    }

    public List<History> getApplicationHistory(String applicationId) {
        return historyRepository.findByRelatedIdAndRelatedType(applicationId, "application");
    }

    public List<History> getContractHistory(String contractId) {
        return historyRepository.findByRelatedIdAndRelatedType(contractId, "contract");
    }

    public List<History> getPaymentHistory(String paymentId) {
        return historyRepository.findByRelatedIdAndRelatedType(paymentId, "payment");
    }

    public List<History> getHistoryByType(String historyType) {
        return historyRepository.findByHistoryType(historyType);
    }

    public List<History> getRecentHistory(int limit) {
        return historyRepository.findRecentHistory(limit);
    }

    public List<History> getHistoryByTimeRange(LocalDateTime start, LocalDateTime end) {
        return historyRepository.findByTimeRange(start, end);
    }

    public List<History> getAllHistory() {
        return historyRepository.findAll();
    }
}
