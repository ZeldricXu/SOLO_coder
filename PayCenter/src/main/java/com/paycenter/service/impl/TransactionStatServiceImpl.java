package com.paycenter.service.impl;

import com.paycenter.entity.TransactionStat;
import com.paycenter.repository.TransactionStatRepository;
import com.paycenter.service.TransactionStatService;
import com.paycenter.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
public class TransactionStatServiceImpl implements TransactionStatService {

    @Autowired
    private TransactionStatRepository transactionStatRepository;

    @Override
    @Transactional
    public TransactionStat updateStats(String merchantId, LocalDate statDate, boolean success) {
        TransactionStat stat = transactionStatRepository.findByMerchantIdAndStatDate(merchantId, statDate)
                .orElseGet(() -> createNewStat(merchantId, statDate));
        
        stat.setTransactionCount(stat.getTransactionCount() + 1);
        
        if (success) {
            stat.setSuccessCount(stat.getSuccessCount() + 1);
        } else {
            stat.setFailCount(stat.getFailCount() + 1);
        }
        
        return transactionStatRepository.save(stat);
    }

    @Override
    @Transactional
    public TransactionStat updateRefundStats(String merchantId, LocalDate statDate) {
        TransactionStat stat = transactionStatRepository.findByMerchantIdAndStatDate(merchantId, statDate)
                .orElseGet(() -> createNewStat(merchantId, statDate));
        
        stat.setRefundCount(stat.getRefundCount() + 1);
        
        return transactionStatRepository.save(stat);
    }

    @Override
    public Optional<TransactionStat> getStatsByDate(String merchantId, LocalDate statDate) {
        return transactionStatRepository.findByMerchantIdAndStatDate(merchantId, statDate);
    }

    @Override
    public List<TransactionStat> getStatsByDateRange(String merchantId, LocalDate start, LocalDate end) {
        return transactionStatRepository.findByMerchantIdAndStatDateBetween(merchantId, start, end);
    }

    private TransactionStat createNewStat(String merchantId, LocalDate statDate) {
        return TransactionStat.builder()
                .statId(IdGenerator.generateStatId())
                .merchantId(merchantId)
                .statDate(statDate)
                .transactionCount(0)
                .totalAmount(BigDecimal.ZERO)
                .successCount(0)
                .failCount(0)
                .refundCount(0)
                .build();
    }
}
