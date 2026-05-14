package com.paycenter.service;

import com.paycenter.dto.SettlementQueryRequest;
import com.paycenter.entity.Settlement;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SettlementService {
    Settlement calculateAndExecuteSettlement(String merchantId, LocalDate settlementDate);
    Optional<Settlement> getSettlementById(String settlementId);
    List<Settlement> getSettlementsByMerchant(String merchantId);
    List<Settlement> querySettlements(SettlementQueryRequest request);
    void processDailySettlement();
}
