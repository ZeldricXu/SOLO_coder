package com.finance.service;

import com.finance.dto.RecordCreateRequest;
import com.finance.dto.RecordCreateResponse;
import com.finance.entity.Account;
import com.finance.entity.Category;
import com.finance.entity.CategoryMatchTask;
import com.finance.entity.Record;
import com.finance.exception.FinanceException;
import com.finance.repository.RecordRepository;
import com.finance.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecordService {

    private final RecordRepository recordRepository;
    private final AccountService accountService;
    private final CategoryService categoryService;
    private final BudgetService budgetService;
    private final ReportService reportService;
    private final AnalysisService analysisService;
    private final HistoryService historyService;
    private final TransactionTypeService transactionTypeService;
    private final ValidationRuleService validationRuleService;
    private final CategoryMatchTaskService categoryMatchTaskService;
    private final RedisQueueService redisQueueService;

    @Transactional
    public RecordCreateResponse createRecord(RecordCreateRequest request) {
        String accountId = request.getAccount_id();
        String recordType = request.getRecord_type();
        BigDecimal amount = request.getRecord_amount();
        String category = request.getRecord_category();

        validationRuleService.validateRecord(request);

        Account account = accountService.getAccountById(accountId);

        if ("frozen".equals(account.getAccountStatus())) {
            throw FinanceException.accountFrozen(accountId);
        }

        LocalDateTime now = LocalDateTime.now();
        boolean isIncome = transactionTypeService.isIncomeType(recordType);
        boolean affectsBalance = transactionTypeService.affectsBalance(recordType);

        Record record = Record.builder()
                .recordId(IdGenerator.generateRecordId())
                .accountId(accountId)
                .recordType(recordType)
                .recordAmount(amount)
                .recordCategory(category)
                .recordTime(now)
                .recordDesc(request.getRecord_desc())
                .createdAt(now)
                .build();
        recordRepository.save(record);
        log.info("创建收支记录: recordId={}, type={}, amount={}", record.getRecordId(), recordType, amount);

        CategoryMatchTask matchTask = categoryMatchTaskService.createTask(
                record.getRecordId(),
                accountId,
                recordType,
                category,
                RedisQueueService.DEFAULT_QUEUE_KEY
        );
        categoryMatchTaskService.submitTaskToQueue(matchTask);

        Category matchedCategory = categoryService.matchCategory(recordType, category);
        String finalCategory;
        if (matchedCategory != null) {
            finalCategory = matchedCategory.getCategoryName();
        } else {
            finalCategory = category;
            log.warn("使用未匹配的分类: {}", category);
        }

        Account updatedAccount = account;
        if (affectsBalance) {
            updatedAccount = accountService.updateBalance(accountId, amount, isIncome);
        }

        if (transactionTypeService.isExpenseType(recordType)) {
            budgetService.checkAndUpdateBudget(accountId, finalCategory, amount);
        }

        reportService.updateReport(accountId, recordType, amount, now);
        analysisService.updateAnalysis(accountId, recordType, amount, finalCategory, now);
        historyService.recordHistory(accountId, "record_create",
                "创建" + (isIncome ? "收入" : "支出") + "记录: " + amount);

        return RecordCreateResponse.builder()
                .record_id(record.getRecordId())
                .balance(updatedAccount.getAccountBalance())
                .build();
    }

    @Transactional(readOnly = true)
    public Record getRecordById(String recordId) {
        return recordRepository.findById(recordId)
                .orElseThrow(() -> new FinanceException(404, "记录不存在: " + recordId));
    }

    @Transactional(readOnly = true)
    public List<Record> getRecordsByAccount(String accountId) {
        return recordRepository.findByAccountIdOrderByRecordTimeDesc(accountId);
    }

    @Transactional(readOnly = true)
    public List<Record> getRecordsByAccountAndType(String accountId, String recordType) {
        return recordRepository.findByAccountIdAndRecordType(accountId, recordType);
    }

    @Transactional(readOnly = true)
    public List<Record> getRecordsByTimeRange(String accountId, LocalDateTime startTime, LocalDateTime endTime) {
        return recordRepository.findByAccountIdAndRecordTimeBetween(accountId, startTime, endTime);
    }

    @Transactional(readOnly = true)
    public List<Record> getRecordsByCategory(String accountId, String category) {
        return recordRepository.findByAccountIdAndRecordCategory(accountId, category);
    }

    @Transactional(readOnly = true)
    public Long countRecords(String accountId) {
        return recordRepository.countByAccountId(accountId);
    }
}
