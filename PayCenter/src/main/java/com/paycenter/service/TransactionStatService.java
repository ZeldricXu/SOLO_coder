package com.paycenter.service;

import com.paycenter.entity.TransactionStat;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface TransactionStatService {
    TransactionStat updateStats(String merchantId, LocalDate statDate, boolean success);
    TransactionStat updateRefundStats(String merchantId, LocalDate statDate);
    Optional<TransactionStat> getStatsByDate(String merchantId, LocalDate statDate);
    List<TransactionStat> getStatsByDateRange(String merchantId, LocalDate start, LocalDate end);
}
