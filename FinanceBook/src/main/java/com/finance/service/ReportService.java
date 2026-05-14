package com.finance.service;

import com.finance.dto.ReportQueryRequest;
import com.finance.dto.ReportQueryResponse;
import com.finance.entity.Report;
import com.finance.exception.FinanceException;
import com.finance.repository.RecordRepository;
import com.finance.repository.ReportRepository;
import com.finance.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    private final ReportRepository reportRepository;
    private final RecordRepository recordRepository;
    private final AccountService accountService;

    @Transactional
    public void updateReport(String accountId, String recordType, BigDecimal amount, LocalDateTime time) {
        String period = YearMonth.from(time).toString();

        Optional<Report> existingReport = reportRepository.findByAccountIdAndReportPeriod(accountId, period);

        Report report;
        LocalDateTime now = LocalDateTime.now();

        if (existingReport.isPresent()) {
            report = existingReport.get();
            if ("income".equals(recordType)) {
                report.setReportIncome(report.getReportIncome().add(amount));
            } else {
                report.setReportExpense(report.getReportExpense().add(amount));
            }
            report.setReportBalance(report.getReportIncome().subtract(report.getReportExpense()));
            report.setGeneratedAt(now);
        } else {
            BigDecimal income = "income".equals(recordType) ? amount : BigDecimal.ZERO;
            BigDecimal expense = "expense".equals(recordType) ? amount : BigDecimal.ZERO;
            report = Report.builder()
                    .reportId(IdGenerator.generateReportId())
                    .accountId(accountId)
                    .reportPeriod(period)
                    .reportIncome(income)
                    .reportExpense(expense)
                    .reportBalance(income.subtract(expense))
                    .generatedAt(now)
                    .build();
        }

        reportRepository.save(report);
        log.debug("更新报表: accountId={}, period={}, income={}, expense={}",
                accountId, period, report.getReportIncome(), report.getReportExpense());
    }

    @Transactional(readOnly = true)
    public ReportQueryResponse queryReport(ReportQueryRequest request) {
        String accountId = request.getAccount_id();
        accountService.getAccountById(accountId);

        LocalDateTime startTime;
        LocalDateTime endTime;
        String period;

        if (request.getPeriod() != null && !request.getPeriod().isEmpty()) {
            YearMonth yearMonth = YearMonth.parse(request.getPeriod());
            startTime = yearMonth.atDay(1).atStartOfDay();
            endTime = yearMonth.atEndOfMonth().atTime(23, 59, 59);
            period = request.getPeriod();
        } else if (request.getStart_date() != null && request.getEnd_date() != null) {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            startTime = LocalDateTime.parse(request.getStart_date() + "T00:00:00");
            endTime = LocalDateTime.parse(request.getEnd_date() + "T23:59:59");
            period = request.getStart_date() + " ~ " + request.getEnd_date();
        } else {
            YearMonth currentMonth = YearMonth.now();
            startTime = currentMonth.atDay(1).atStartOfDay();
            endTime = currentMonth.atEndOfMonth().atTime(23, 59, 59);
            period = currentMonth.toString();
        }

        BigDecimal income = recordRepository.sumIncomeByAccountIdAndTimeRange(accountId, startTime, endTime);
        BigDecimal expense = recordRepository.sumExpenseByAccountIdAndTimeRange(accountId, startTime, endTime);
        BigDecimal balance = income.subtract(expense);

        log.info("查询报表: accountId={}, period={}, income={}, expense={}", accountId, period, income, expense);

        return ReportQueryResponse.builder()
                .report(ReportQueryResponse.ReportInfo.builder()
                        .income(income)
                        .expense(expense)
                        .balance(balance)
                        .period(period)
                        .build())
                .build();
    }

    @Transactional(readOnly = true)
    public Report getReportById(String reportId) {
        return reportRepository.findById(reportId)
                .orElseThrow(() -> new FinanceException(404, "报表不存在: " + reportId));
    }

    @Transactional(readOnly = true)
    public List<Report> getReportsByAccount(String accountId) {
        return reportRepository.findByAccountIdOrderByReportPeriodDesc(accountId);
    }

    @Transactional(readOnly = true)
    public Optional<Report> getReportByPeriod(String accountId, String period) {
        return reportRepository.findByAccountIdAndReportPeriod(accountId, period);
    }

    @Transactional
    public Report generateMonthlyReport(String accountId, YearMonth month) {
        accountService.getAccountById(accountId);

        LocalDateTime startTime = month.atDay(1).atStartOfDay();
        LocalDateTime endTime = month.atEndOfMonth().atTime(23, 59, 59);
        String period = month.toString();

        BigDecimal income = recordRepository.sumIncomeByAccountIdAndTimeRange(accountId, startTime, endTime);
        BigDecimal expense = recordRepository.sumExpenseByAccountIdAndTimeRange(accountId, startTime, endTime);

        Optional<Report> existingReport = reportRepository.findByAccountIdAndReportPeriod(accountId, period);

        Report report;
        if (existingReport.isPresent()) {
            report = existingReport.get();
            report.setReportIncome(income);
            report.setReportExpense(expense);
            report.setReportBalance(income.subtract(expense));
            report.setGeneratedAt(LocalDateTime.now());
        } else {
            report = Report.builder()
                    .reportId(IdGenerator.generateReportId())
                    .accountId(accountId)
                    .reportPeriod(period)
                    .reportIncome(income)
                    .reportExpense(expense)
                    .reportBalance(income.subtract(expense))
                    .generatedAt(LocalDateTime.now())
                    .build();
        }

        Report saved = reportRepository.save(report);
        log.info("生成月度报表: accountId={}, period={}", accountId, period);
        return saved;
    }
}
