package com.paycenter.service.impl;

import com.paycenter.dto.SettlementQueryRequest;
import com.paycenter.entity.Account;
import com.paycenter.entity.Refund;
import com.paycenter.entity.Settlement;
import com.paycenter.entity.Transaction;
import com.paycenter.entity.TransactionStat;
import com.paycenter.service.AccountService;
import com.paycenter.service.QueryService;
import com.paycenter.service.RefundService;
import com.paycenter.service.SettlementService;
import com.paycenter.service.TransactionService;
import com.paycenter.service.TransactionStatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class QueryServiceImpl implements QueryService {

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private SettlementService settlementService;

    @Autowired
    private TransactionStatService transactionStatService;

    @Autowired
    private AccountService accountService;

    @Autowired
    private RefundService refundService;

    @Override
    public List<Transaction> queryTransactions(String merchantId, LocalDateTime start, LocalDateTime end) {
        return transactionService.getTransactionsByMerchant(merchantId, start, end);
    }

    @Override
    public List<Settlement> querySettlements(SettlementQueryRequest request) {
        return settlementService.querySettlements(request);
    }

    @Override
    public List<TransactionStat> queryStats(String merchantId, LocalDate start, LocalDate end) {
        return transactionStatService.getStatsByDateRange(merchantId, start, end);
    }

    @Override
    public Map<String, Object> getTransactionDetail(String transactionId) {
        Map<String, Object> result = new HashMap<>();
        
        Optional<Transaction> transactionOpt = transactionService.getTransactionById(transactionId);
        if (transactionOpt.isPresent()) {
            result.put("transaction", transactionOpt.get());
            
            List<Refund> refunds = refundService.getRefundsByTransaction(transactionId);
            result.put("refunds", refunds);
        }
        
        return result;
    }

    @Override
    public Map<String, Object> getAccountSummary(String merchantId) {
        Map<String, Object> result = new HashMap<>();
        
        Optional<Account> accountOpt = accountService.getAccountByMerchantId(merchantId);
        if (accountOpt.isPresent()) {
            Account account = accountOpt.get();
            result.put("account", account);
        } else {
            Account newAccount = accountService.createAccount(merchantId);
            result.put("account", newAccount);
        }
        
        return result;
    }
}
