package com.houserental.service;

import com.houserental.entity.Contract;
import com.houserental.entity.Payment;
import com.houserental.entity.PaymentTask;
import com.houserental.exception.HouseRentalException;
import com.houserental.repository.PaymentRepository;
import com.houserental.util.IdGenerator;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class PaymentWorkerService {

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private ContractService contractService;

    @Autowired
    private LandlordService landlordService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private StatisticsService statisticsService;

    @Autowired
    private RedisPaymentQueueService redisPaymentQueueService;

    @Value("${payment.max-retry-count:3}")
    private int maxRetryCount;

    @Value("${payment.retry-delay-minutes:5}")
    private int retryDelayMinutes;

    private final Map<String, Integer> retryCounts = new ConcurrentHashMap<>();
    private final Map<String, LocalDateTime> lastRetryTime = new ConcurrentHashMap<>();
    private final List<Map<String, Object>> paymentResults = new ArrayList<>();
    private final List<String> completedPayments = new ArrayList<>();

    private ExecutorService workerExecutor;
    private volatile boolean running = false;

    public static class PaymentStatus {
        public static final String PENDING = "pending";
        public static final String PROCESSING = "processing";
        public static final String PAID = "paid";
        public static final String FAILED = "failed";
        public static final String RETRYING = "retrying";
    }

    @PostConstruct
    public void init() {
        workerExecutor = Executors.newSingleThreadExecutor();
        startWorker();
    }

    public void startWorker() {
        if (running) {
            return;
        }
        running = true;
        workerExecutor.submit(this::workerLoop);
    }

    public void stopWorker() {
        running = false;
    }

    private void workerLoop() {
        while (running) {
            try {
                PaymentTask task = redisPaymentQueueService.dequeuePaymentTask();
                if (task != null) {
                    processPaymentTask(task);
                }
            } catch (Exception e) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    @Async
    public CompletablePaymentResult processPaymentAsync(String contractId, double amount, 
                                                         String paymentMethod, String paymentPeriod) {
        Contract contract = contractService.getContractById(contractId);
        validateContractForPayment(contract);

        String paymentPeriodFinal = paymentPeriod != null ? paymentPeriod : getCurrentPeriod();
        String paymentId = IdGenerator.generatePaymentId();

        PaymentTask task = PaymentTask.create(
                paymentId,
                contractId,
                contract.getTenantId(),
                contract.getLandlordId(),
                amount,
                paymentMethod,
                paymentPeriodFinal
        );

        redisPaymentQueueService.enqueuePaymentTask(task);

        return new CompletablePaymentResult(paymentId, contractId, contract.getTenantId(), PaymentStatus.PROCESSING);
    }

    private void processPaymentTask(PaymentTask task) {
        try {
            Contract contract = contractService.getContractById(task.getContractId());
            Payment payment = createPendingPayment(task.getPaymentId(), contract, task.getAmount(), task.getPaymentPeriod());

            Map<String, Object> result = new HashMap<>();
            result.put("paymentId", task.getPaymentId());
            result.put("contractId", task.getContractId());
            result.put("tenantId", task.getTenantId());
            result.put("landlordId", task.getLandlordId());
            result.put("amount", task.getAmount());
            result.put("period", task.getPaymentPeriod());
            result.put("method", task.getPaymentMethod());
            result.put("startedAt", LocalDateTime.now());

            Thread.sleep(100);

            boolean success = executePaymentGateway(task.getPaymentId(), task.getAmount(), task.getPaymentMethod());

            if (success) {
                markPaymentAsPaid(payment, task.getPaymentMethod());
                result.put("status", PaymentStatus.PAID);
                result.put("completedAt", LocalDateTime.now());
                result.put("retryCount", task.getRetryCount());

                landlordService.addIncome(contract.getLandlordId(), task.getAmount());
                statisticsService.addRentAmount(task.getAmount());

                completedPayments.add(task.getPaymentId());

                redisPaymentQueueService.removeRetryTask(task.getTaskId());
            } else {
                result.put("status", PaymentStatus.FAILED);
                result.put("completedAt", LocalDateTime.now());
                result.put("retryCount", task.getRetryCount());
                result.put("error", "支付网关返回失败");

                task.setStatus(PaymentStatus.FAILED);
                task.setErrorMessage("支付网关返回失败");
                scheduleRetry(task);
            }

            paymentResults.add(result);

            recordPaymentHistory(payment, contract, success ? "PAID" : "FAILED",
                    success ? "后台支付成功" : "后台支付失败，准备重试");

        } catch (Exception e) {
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("paymentId", task.getPaymentId());
            errorResult.put("contractId", task.getContractId());
            errorResult.put("tenantId", task.getTenantId());
            errorResult.put("landlordId", task.getLandlordId());
            errorResult.put("amount", task.getAmount());
            errorResult.put("period", task.getPaymentPeriod());
            errorResult.put("status", PaymentStatus.FAILED);
            errorResult.put("error", e.getMessage());
            errorResult.put("completedAt", LocalDateTime.now());
            paymentResults.add(errorResult);

            task.setStatus(PaymentStatus.FAILED);
            task.setErrorMessage(e.getMessage());
            scheduleRetry(task);
        }
    }

    public Payment createPendingPayment(String paymentId, Contract contract, double amount, String paymentPeriod) {
        Payment payment = new Payment();
        payment.setPaymentId(paymentId);
        payment.setContractId(contract.getContractId());
        payment.setTenantId(contract.getTenantId());
        payment.setPaymentAmount(amount);
        payment.setPaymentPeriod(paymentPeriod);
        payment.setPaymentStatus(PaymentStatus.PENDING);
        return paymentRepository.save(payment);
    }

    public void markPaymentAsPaid(Payment payment, String paymentMethod) {
        payment.setPaymentStatus(PaymentStatus.PAID);
        payment.setPaymentMethod(paymentMethod);
        payment.setPaidAt(LocalDateTime.now());
        paymentRepository.save(payment);
    }

    public boolean executePaymentGateway(String paymentId, double amount, String paymentMethod) {
        return Math.random() > 0.3;
    }

    public boolean executePaymentGatewayDeterministic(String paymentId, double amount, String paymentMethod, boolean shouldSucceed) {
        return shouldSucceed;
    }

    public void scheduleRetry(PaymentTask task) {
        if (task.getRetryCount() < maxRetryCount) {
            task.setRetryCount(task.getRetryCount() + 1);
            task.setLastRetryAt(LocalDateTime.now());
            task.setStatus(PaymentStatus.RETRYING);
            redisPaymentQueueService.enqueueRetryTask(task);
        } else {
            task.setStatus("DEAD_LETTER");
            redisPaymentQueueService.moveToDeadLetter(task);
        }
    }

    @Scheduled(fixedRateString = "${payment.retry-check-interval-ms:60000}")
    public void retryFailedPayments() {
        LocalDateTime now = LocalDateTime.now();
        List<PaymentTask> retryTasks = redisPaymentQueueService.getAllRetryTasks();

        for (PaymentTask task : retryTasks) {
            if (task.getLastRetryAt() == null) {
                continue;
            }

            long minutesSinceLastRetry = java.time.temporal.ChronoUnit.MINUTES.between(task.getLastRetryAt(), now);
            if (minutesSinceLastRetry >= retryDelayMinutes) {
                Optional<Payment> paymentOpt = paymentRepository.findByPaymentId(task.getPaymentId());
                if (paymentOpt.isPresent()) {
                    Payment payment = paymentOpt.get();
                    Contract contract = contractService.getContractById(payment.getContractId());

                    boolean success = executePaymentGateway(task.getPaymentId(), task.getAmount(), task.getPaymentMethod());

                    if (success) {
                        markPaymentAsPaid(payment, task.getPaymentMethod());
                        redisPaymentQueueService.removeRetryTask(task.getTaskId());

                        landlordService.addIncome(contract.getLandlordId(), task.getAmount());
                        statisticsService.addRentAmount(task.getAmount());

                        recordPaymentHistory(payment, contract, "PAID", "重试支付成功");
                    } else {
                        int newRetryCount = task.getRetryCount() + 1;
                        task.setRetryCount(newRetryCount);
                        task.setLastRetryAt(now);

                        if (newRetryCount >= maxRetryCount) {
                            task.setStatus("DEAD_LETTER");
                            redisPaymentQueueService.removeRetryTask(task.getTaskId());
                            redisPaymentQueueService.moveToDeadLetter(task);
                            recordPaymentHistory(payment, contract, "FAILED", "重试" + maxRetryCount + "次后仍失败");
                        } else {
                            redisPaymentQueueService.enqueueRetryTask(task);
                        }
                    }
                }
            }
        }
    }

    public void validateContractForPayment(Contract contract) {
        if ("terminated".equals(contract.getContractStatus())) {
            throw new HouseRentalException(400, "合同已终止，无法支付");
        }
        if ("expired".equals(contract.getContractStatus())) {
            throw new HouseRentalException(400, "合同已过期，无法支付");
        }
    }

    private String getCurrentPeriod() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
    }

    private void recordPaymentHistory(Payment payment, Contract contract, String action, String description) {
        historyService.recordPaymentHistory(
                payment.getPaymentId(),
                action,
                description,
                contract.getHouseId(),
                contract.getTenantId(),
                contract.getLandlordId()
        );
    }

    public int getRetryCount(String paymentId) {
        return retryCounts.getOrDefault(paymentId, 0);
    }

    public void setRetryCount(String paymentId, int count) {
        if (count <= 0) {
            retryCounts.remove(paymentId);
        } else {
            retryCounts.put(paymentId, count);
        }
    }

    public boolean hasReachedMaxRetries(String paymentId) {
        return getRetryCount(paymentId) >= maxRetryCount;
    }

    public Map<String, Object> getPaymentResult(String paymentId) {
        return paymentResults.stream()
                .filter(r -> paymentId.equals(r.get("paymentId")))
                .reduce((first, second) -> second)
                .orElse(null);
    }

    public List<Map<String, Object>> getAllPaymentResults() {
        return new ArrayList<>(paymentResults);
    }

    public List<String> getCompletedPayments() {
        return new ArrayList<>(completedPayments);
    }

    public void clearPaymentResults() {
        paymentResults.clear();
        completedPayments.clear();
        retryCounts.clear();
        lastRetryTime.clear();
    }

    public void setMaxRetryCount(int count) {
        this.maxRetryCount = count;
    }

    public void setRetryDelayMinutes(int minutes) {
        this.retryDelayMinutes = minutes;
    }

    public int getMaxRetryCount() {
        return maxRetryCount;
    }

    public int getRetryDelayMinutes() {
        return retryDelayMinutes;
    }

    public static class CompletablePaymentResult {
        private final String paymentId;
        private final String contractId;
        private final String tenantId;
        private final String status;

        public CompletablePaymentResult(String paymentId, String contractId, String tenantId, String status) {
            this.paymentId = paymentId;
            this.contractId = contractId;
            this.tenantId = tenantId;
            this.status = status;
        }

        public String getPaymentId() {
            return paymentId;
        }

        public String getContractId() {
            return contractId;
        }

        public String getTenantId() {
            return tenantId;
        }

        public String getStatus() {
            return status;
        }
    }
}
