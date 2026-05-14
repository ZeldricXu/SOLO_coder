package com.invoice.mgmt.verify.service;

import com.invoice.mgmt.common.dto.InvoiceVerifyRequest;
import com.invoice.mgmt.common.dto.InvoiceVerifyResponse;
import com.invoice.mgmt.common.entity.Invoice;
import com.invoice.mgmt.common.entity.InvoiceVerify;
import com.invoice.mgmt.common.enums.InvoiceStatusEnum;
import com.invoice.mgmt.common.enums.VerifyResultEnum;
import com.invoice.mgmt.common.enums.VerifyTypeEnum;
import com.invoice.mgmt.common.exception.InvoiceException;
import com.invoice.mgmt.common.redis.RedisQueueService;
import com.invoice.mgmt.common.util.DateTimeUtil;
import com.invoice.mgmt.common.util.IdGenerator;
import com.invoice.mgmt.history.service.InvoiceHistoryService;
import com.invoice.mgmt.issue.service.InvoiceIssueService;
import com.invoice.mgmt.statistics.service.InvoiceStatisticsService;
import com.invoice.mgmt.status.service.InvoiceStatusService;
import com.invoice.mgmt.verify.dto.VerifyTaskDTO;
import com.invoice.mgmt.verify.mapper.InvoiceVerifyMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

@Service
public class AsyncInvoiceVerifyService {
    private static final Logger logger = LoggerFactory.getLogger(AsyncInvoiceVerifyService.class);
    private static final Pattern INVOICE_NO_PATTERN = Pattern.compile("^\\d{8,20}$");

    private static final String QUEUE_NAME = "verify_tasks";
    private static final String RESULT_PREFIX = "verify:result:";

    @Value("${invoice.verify.online.timeout:5000}")
    private int onlineTimeoutMs;

    @Value("${invoice.verify.online.mock:true}")
    private boolean mockOnline;

    @Value("${invoice.verify.max-retry:3}")
    private int maxRetry;

    @Value("${invoice.verify.retry-delay:1000}")
    private int retryDelayMs;

    @Value("${invoice.verify.use-redis:true}")
    private boolean useRedis;

    @Autowired
    private InvoiceVerifyMapper verifyMapper;

    @Autowired
    private InvoiceIssueService invoiceIssueService;

    @Autowired
    private InvoiceStatusService invoiceStatusService;

    @Autowired
    private InvoiceStatisticsService invoiceStatisticsService;

    @Autowired
    private InvoiceHistoryService invoiceHistoryService;

    @Autowired(required = false)
    private RedisQueueService redisQueueService;

    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private final BlockingQueue<VerifyTask> verifyTaskQueue = new LinkedBlockingQueue<>();
    private final ConcurrentHashMap<String, VerifyResultHolder> resultCache = new ConcurrentHashMap<>();
    private final AtomicInteger retryCounter = new AtomicInteger(0);
    private volatile boolean workerRunning = false;
    private ExecutorService executorService;

    public static class VerifyTask {
        private final InvoiceVerifyRequest request;
        private final String taskId;
        private final long submitTime;
        private int retryCount;

        public VerifyTask(InvoiceVerifyRequest request) {
            this.request = request;
            this.taskId = IdGenerator.generateVerifyId();
            this.submitTime = System.currentTimeMillis();
            this.retryCount = 0;
        }

        public VerifyTask(VerifyTaskDTO dto) {
            this.request = dto.getRequest();
            this.taskId = dto.getTaskId();
            this.submitTime = dto.getSubmitTime();
            this.retryCount = dto.getRetryCount();
        }

        public InvoiceVerifyRequest getRequest() { return request; }
        public String getTaskId() { return taskId; }
        public long getSubmitTime() { return submitTime; }
        public int getRetryCount() { return retryCount; }
        public void incrementRetry() { this.retryCount++; }

        public VerifyTaskDTO toDTO() {
            return VerifyTaskDTO.builder()
                    .taskId(taskId)
                    .request(request)
                    .submitTime(submitTime)
                    .retryCount(retryCount)
                    .build();
        }
    }

    public static class VerifyResultHolder {
        private volatile InvoiceVerifyResponse result;
        private volatile Exception error;
        private volatile boolean completed;

        public synchronized void setResult(InvoiceVerifyResponse result) {
            this.result = result;
            this.completed = true;
            this.notifyAll();
        }

        public synchronized void setError(Exception error) {
            this.error = error;
            this.completed = true;
            this.notifyAll();
        }

        public synchronized InvoiceVerifyResponse waitForResult(long timeoutMs) throws Exception {
            if (!completed) {
                this.wait(timeoutMs);
            }
            if (error != null) {
                throw error;
            }
            return result;
        }

        public boolean isCompleted() { return completed; }

        public String toJson(com.fasterxml.jackson.databind.ObjectMapper mapper) throws Exception {
            VerifyResultDTO dto = VerifyResultDTO.builder()
                    .result(result)
                    .error(error != null ? error.getMessage() : null)
                    .completed(completed)
                    .build();
            return mapper.writeValueAsString(dto);
        }

        public static VerifyResultHolder fromJson(String json, com.fasterxml.jackson.databind.ObjectMapper mapper) throws Exception {
            VerifyResultDTO dto = mapper.readValue(json, VerifyResultDTO.class);
            VerifyResultHolder holder = new VerifyResultHolder();
            holder.result = dto.result;
            holder.error = dto.error != null ? new Exception(dto.error) : null;
            holder.completed = dto.completed;
            return holder;
        }
    }

    public static class VerifyResultDTO {
        public InvoiceVerifyResponse result;
        public String error;
        public boolean completed;

        public static VerifyResultDTO builder() {
            return new VerifyResultDTO();
        }

        public VerifyResultDTO result(InvoiceVerifyResponse result) {
            this.result = result;
            return this;
        }

        public VerifyResultDTO error(String error) {
            this.error = error;
            return this;
        }

        public VerifyResultDTO completed(boolean completed) {
            this.completed = completed;
            return this;
        }

        public VerifyResultDTO build() {
            return this;
        }
    }

    @PostConstruct
    public void init() {
        executorService = Executors.newSingleThreadExecutor();
        startWorkerIfNeeded();
        logger.info("异步验证服务初始化完成, Redis模式: {}", useRedis);
    }

    @PreDestroy
    public void destroy() {
        logger.info("异步验证服务停止中...");
        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        logger.info("异步验证服务已停止");
    }

    public String submitVerifyAsync(InvoiceVerifyRequest request) {
        String invoiceNo = request.getInvoiceNo();
        String invoiceCode = request.getInvoiceCode();

        VerifyResultHolder holder = new VerifyResultHolder();
        VerifyTask task = new VerifyTask(request);

        if (useRedis && redisQueueService != null) {
            resultCache.put(task.getTaskId(), holder);
            boolean enqueued = redisQueueService.push(QUEUE_NAME, task.toDTO());
            if (!enqueued) {
                logger.warn("Redis入队失败，回退到内存队列");
                verifyTaskQueue.offer(task);
            }
        } else {
            String quickCheckKey = "quick_" + invoiceNo + "_" + invoiceCode;
            resultCache.put(quickCheckKey, holder);
            verifyTaskQueue.offer(task);
        }

        logger.info("验证任务已提交: taskId={}, invoiceNo={}, 队列={}", task.getTaskId(), invoiceNo, useRedis ? "Redis" : "内存");
        return task.getTaskId();
    }

    public InvoiceVerifyResponse getVerifyResult(String taskId, long timeoutMs) throws Exception {
        VerifyResultHolder holder = resultCache.get(taskId);

        if (holder == null && useRedis) {
            String resultKey = RESULT_PREFIX + taskId;
            String resultJson = redisTemplate.opsForValue().get(resultKey);
            if (resultJson != null) {
                holder = VerifyResultHolder.fromJson(resultJson, objectMapper);
                resultCache.put(taskId, holder);
            }
        }

        if (holder == null && !useRedis) {
            for (VerifyResultHolder h : resultCache.values()) {
                if (h.result != null && taskId.equals(h.result.getVerifyId())) {
                    holder = h;
                    break;
                }
            }
        }

        if (holder == null) {
            throw new InvoiceException(404, "验证任务不存在");
        }
        return holder.waitForResult(timeoutMs);
    }

    private void startWorkerIfNeeded() {
        if (!workerRunning) {
            synchronized (this) {
                if (!workerRunning) {
                    workerRunning = true;
                    executorService.submit(this::runVerifyWorker);
                }
            }
        }
    }

    private void runVerifyWorker() {
        logger.info("验证Worker启动, 队列模式: {}", useRedis ? "Redis" : "内存");
        while (!Thread.currentThread().isInterrupted()) {
            try {
                VerifyTask task = null;

                if (useRedis && redisQueueService != null) {
                    VerifyTaskDTO dto = redisQueueService.bPop(QUEUE_NAME, VerifyTaskDTO.class, 1000);
                    if (dto != null) {
                        task = new VerifyTask(dto);
                    }
                } else {
                    task = verifyTaskQueue.poll(100, TimeUnit.MILLISECONDS);
                }

                if (task != null) {
                    processTask(task);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("验证Worker处理异常", e);
            }
        }
        logger.info("验证Worker停止");
    }

    private void processTask(VerifyTask task) {
        InvoiceVerifyRequest request = task.getRequest();
        String taskId = task.getTaskId();
        VerifyResultHolder holder = resultCache.get(taskId);

        if (holder == null) {
            holder = new VerifyResultHolder();
            resultCache.put(taskId, holder);
        }

        try {
            InvoiceVerifyResponse response = executeVerifyWithRetry(task);
            holder.setResult(response);

            if (useRedis) {
                saveResultToRedis(taskId, holder);
            }

            logger.info("验证任务完成: taskId={}", task.getTaskId());
        } catch (Exception e) {
            if (task.getRetryCount() < maxRetry) {
                task.incrementRetry();
                retryCounter.incrementAndGet();
                logger.info("验证任务重试: taskId={}, retryCount={}", task.getTaskId(), task.getRetryCount());
                try {
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                if (useRedis && redisQueueService != null) {
                    redisQueueService.push(QUEUE_NAME, task.toDTO());
                } else {
                    verifyTaskQueue.offer(task);
                }
                return;
            }
            holder.setError(e);
            if (useRedis) {
                saveResultToRedis(taskId, holder);
            }
            logger.error("验证任务最终失败: taskId={}", task.getTaskId(), e);
        }
    }

    private void saveResultToRedis(String taskId, VerifyResultHolder holder) {
        try {
            String resultKey = RESULT_PREFIX + taskId;
            String json = holder.toJson(objectMapper);
            redisTemplate.opsForValue().set(resultKey, json, 24, TimeUnit.HOURS);
        } catch (Exception e) {
            logger.error("保存验证结果到Redis失败: taskId={}", taskId, e);
        }
    }

    public InvoiceVerifyResponse executeVerifyWithRetry(VerifyTask task) {
        InvoiceVerifyRequest request = task.getRequest();
        String invoiceNo = request.getInvoiceNo();
        String invoiceCode = request.getInvoiceCode();
        String verifyType = request.getVerifyType() != null ? request.getVerifyType() : VerifyTypeEnum.ONLINE.getCode();

        Invoice invoice;
        try {
            invoice = invoiceIssueService.getByNoAndCode(invoiceNo, invoiceCode);
        } catch (InvoiceException e) {
            logger.warn("发票不存在: invoiceNo={}, invoiceCode={}", invoiceNo, invoiceCode);
            saveVerifyRecord(null, invoiceNo, invoiceCode, verifyType, VerifyResultEnum.INVALID, "发票不存在");
            throw InvoiceException.verifyFailed();
        }

        String currentStatus = invoice.getInvoiceStatus();
        if (InvoiceStatusEnum.CANCELLED.getCode().equals(currentStatus)
                || InvoiceStatusEnum.INVALID.getCode().equals(currentStatus)) {
            logger.warn("发票状态无效: invoiceId={}, status={}", invoice.getInvoiceId(), currentStatus);
            saveVerifyRecord(invoice.getInvoiceId(), invoiceNo, invoiceCode, verifyType, VerifyResultEnum.INVALID, "发票状态无效");
            invoiceStatisticsService.recordVerify(false);
            throw InvoiceException.verifyFailed();
        }

        VerifyResultEnum result;
        String detail;

        if (VerifyTypeEnum.ONLINE.getCode().equals(verifyType)) {
            result = doOnlineVerify(invoice);
            detail = "在线验证" + (result == VerifyResultEnum.VALID ? "通过" : "未通过");
        } else {
            result = doLocalVerify(invoice);
            detail = "本地验证" + (result == VerifyResultEnum.VALID ? "通过" : "未通过");
        }

        saveVerifyRecord(invoice.getInvoiceId(), invoiceNo, invoiceCode, verifyType, result, detail);
        invoiceStatisticsService.recordVerify(result == VerifyResultEnum.VALID);
        invoiceHistoryService.recordVerify(invoice.getInvoiceId(), result.getCode(), request.getOperator());

        if (result == VerifyResultEnum.VALID) {
            if (invoiceStatusService.canVerify(currentStatus)) {
                invoiceStatusService.verify(invoice.getInvoiceId(), request.getOperator());
            }
        }

        return InvoiceVerifyResponse.builder()
                .verifyId(task.getTaskId())
                .verifyResult(result.getCode())
                .verifyType(verifyType)
                .verifiedAt(DateTimeUtil.formatFull(DateTimeUtil.now()))
                .verifyDetail(detail)
                .build();
    }

    public VerifyResultEnum doOnlineVerify(Invoice invoice) {
        if (mockOnline) {
            return VerifyResultEnum.VALID;
        }
        try {
            Thread.sleep(Math.min(onlineTimeoutMs, 100));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return VerifyResultEnum.VALID;
    }

    public VerifyResultEnum doLocalVerify(Invoice invoice) {
        if (invoice.getInvoiceNo() == null || !INVOICE_NO_PATTERN.matcher(invoice.getInvoiceNo()).matches()) {
            return VerifyResultEnum.INVALID;
        }
        if (invoice.getInvoiceAmount() == null || invoice.getInvoiceAmount().compareTo(java.math.BigDecimal.ZERO) <= 0) {
            return VerifyResultEnum.INVALID;
        }
        if (invoice.getIssueTime() == null || invoice.getIssueTime().isAfter(DateTimeUtil.now())) {
            return VerifyResultEnum.INVALID;
        }
        return VerifyResultEnum.VALID;
    }

    private void saveVerifyRecord(String invoiceId, String invoiceNo, String invoiceCode,
                                  String verifyType, VerifyResultEnum result, String detail) {
        InvoiceVerify verify = InvoiceVerify.builder()
                .verifyId(IdGenerator.generateVerifyId())
                .invoiceId(invoiceId)
                .verifyType(verifyType)
                .verifyResult(result.getCode())
                .verifySource(VerifyTypeEnum.ONLINE.getCode().equals(verifyType) ? "tax_system" : "local")
                .verifyDetail(detail)
                .verifiedAt(DateTimeUtil.now())
                .createdAt(DateTimeUtil.now())
                .build();
        verifyMapper.insert(verify);
    }

    public Queue<VerifyTask> getVerifyTaskQueue() { return verifyTaskQueue; }
    public ConcurrentHashMap<String, VerifyResultHolder> getResultCache() { return resultCache; }
    public int getTotalRetryCount() { return retryCounter.get(); }
    public void resetRetryCounter() { retryCounter.set(0); }
    public boolean isWorkerRunning() { return workerRunning; }
    public long getRedisQueueSize() {
        return useRedis && redisQueueService != null ? redisQueueService.size(QUEUE_NAME) : 0;
    }

    public InvoiceVerify getById(String verifyId) { return verifyMapper.findById(verifyId); }
    public List<InvoiceVerify> getByInvoice(String invoiceId) { return verifyMapper.findByInvoiceId(invoiceId); }
}
