package com.invoice.mgmt.reimburse.service;

import com.invoice.mgmt.common.dto.InvoiceReimburseRequest;
import com.invoice.mgmt.common.dto.InvoiceReimburseResponse;
import com.invoice.mgmt.common.entity.Invoice;
import com.invoice.mgmt.common.entity.InvoiceReimburse;
import com.invoice.mgmt.common.enums.ReimburseStatusEnum;
import com.invoice.mgmt.common.exception.InvoiceException;
import com.invoice.mgmt.common.redis.RedisQueueService;
import com.invoice.mgmt.common.util.DateTimeUtil;
import com.invoice.mgmt.common.util.IdGenerator;
import com.invoice.mgmt.history.service.InvoiceHistoryService;
import com.invoice.mgmt.issue.service.InvoiceIssueService;
import com.invoice.mgmt.statistics.service.InvoiceStatisticsService;
import com.invoice.mgmt.status.service.InvoiceStatusService;
import com.invoice.mgmt.reimburse.dto.ReimburseTaskDTO;
import com.invoice.mgmt.reimburse.mapper.InvoiceReimburseMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;

@Service
public class AsyncInvoiceReimburseService {
    private static final Logger logger = LoggerFactory.getLogger(AsyncInvoiceReimburseService.class);

    private static final String QUEUE_NAME = "reimburse_tasks";
    private static final String RESULT_PREFIX = "reimburse:result:";

    public static final int PRIORITY_URGENT = 3;
    public static final int PRIORITY_HIGH = 2;
    public static final int PRIORITY_NORMAL = 1;

    @Value("${invoice.reimburse.use-redis:true}")
    private boolean useRedis;

    @Autowired
    private InvoiceReimburseMapper reimburseMapper;

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

    private final PriorityBlockingQueue<ReimburseTask> reimburseTaskQueue = new PriorityBlockingQueue<>();
    private final ConcurrentHashMap<String, ReimburseResultHolder> resultCache = new ConcurrentHashMap<>();
    private volatile boolean workerRunning = false;
    private ExecutorService executorService;

    public static class ReimburseTask implements Comparable<ReimburseTask> {
        private final InvoiceReimburseRequest request;
        private final String taskId;
        private final int priority;
        private final long submitTime;

        public ReimburseTask(InvoiceReimburseRequest request) {
            this.request = request;
            this.taskId = IdGenerator.generateReimburseId();
            this.priority = determinePriority(request);
            this.submitTime = System.currentTimeMillis();
        }

        public ReimburseTask(ReimburseTaskDTO dto) {
            this.request = dto.getRequest();
            this.taskId = dto.getTaskId();
            this.priority = dto.getPriority();
            this.submitTime = dto.getSubmitTime();
        }

        private int determinePriority(InvoiceReimburseRequest request) {
            String reason = request.getReimburseReason();
            if (reason != null) {
                String lower = reason.toLowerCase();
                if (lower.contains("紧急") || lower.contains("urgent")
                        || lower.contains("医疗") || lower.contains("medical")
                        || lower.contains("意外") || lower.contains("accident")) {
                    return PRIORITY_URGENT;
                }
                if (lower.contains("重要") || lower.contains("important")
                        || lower.contains("出差") || lower.contains("travel")) {
                    return PRIORITY_HIGH;
                }
            }
            return PRIORITY_NORMAL;
        }

        public InvoiceReimburseRequest getRequest() { return request; }
        public String getTaskId() { return taskId; }
        public int getPriority() { return priority; }
        public long getSubmitTime() { return submitTime; }

        public ReimburseTaskDTO toDTO() {
            return ReimburseTaskDTO.builder()
                    .taskId(taskId)
                    .request(request)
                    .priority(priority)
                    .submitTime(submitTime)
                    .build();
        }

        @Override
        public int compareTo(ReimburseTask other) {
            if (this.priority != other.priority) {
                return Integer.compare(other.priority, this.priority);
            }
            return Long.compare(this.submitTime, other.submitTime);
        }
    }

    public static class ReimburseResultHolder {
        private volatile InvoiceReimburseResponse result;
        private volatile Exception error;
        private volatile boolean completed;

        public synchronized void setResult(InvoiceReimburseResponse result) {
            this.result = result;
            this.completed = true;
            this.notifyAll();
        }

        public synchronized void setError(Exception error) {
            this.error = error;
            this.completed = true;
            this.notifyAll();
        }

        public synchronized InvoiceReimburseResponse waitForResult(long timeoutMs) throws Exception {
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
            ReimburseResultDTO dto = ReimburseResultDTO.builder()
                    .result(result)
                    .error(error != null ? error.getMessage() : null)
                    .completed(completed)
                    .build();
            return mapper.writeValueAsString(dto);
        }

        public static ReimburseResultHolder fromJson(String json, com.fasterxml.jackson.databind.ObjectMapper mapper) throws Exception {
            ReimburseResultDTO dto = mapper.readValue(json, ReimburseResultDTO.class);
            ReimburseResultHolder holder = new ReimburseResultHolder();
            holder.result = dto.result;
            holder.error = dto.error != null ? new Exception(dto.error) : null;
            holder.completed = dto.completed;
            return holder;
        }
    }

    public static class ReimburseResultDTO {
        public InvoiceReimburseResponse result;
        public String error;
        public boolean completed;

        public static ReimburseResultDTO builder() {
            return new ReimburseResultDTO();
        }

        public ReimburseResultDTO result(InvoiceReimburseResponse result) {
            this.result = result;
            return this;
        }

        public ReimburseResultDTO error(String error) {
            this.error = error;
            return this;
        }

        public ReimburseResultDTO completed(boolean completed) {
            this.completed = completed;
            return this;
        }

        public ReimburseResultDTO build() {
            return this;
        }
    }

    @PostConstruct
    public void init() {
        executorService = Executors.newSingleThreadExecutor();
        startWorkerIfNeeded();
        logger.info("异步报销服务初始化完成, Redis模式: {}", useRedis);
    }

    @PreDestroy
    public void destroy() {
        logger.info("异步报销服务停止中...");
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
        logger.info("异步报销服务已停止");
    }

    public String submitReimburseAsync(InvoiceReimburseRequest request) {
        ReimburseResultHolder holder = new ReimburseResultHolder();
        ReimburseTask task = new ReimburseTask(request);

        if (useRedis && redisQueueService != null) {
            resultCache.put(task.getTaskId(), holder);
            boolean enqueued = redisQueueService.push(QUEUE_NAME, task.toDTO());
            if (!enqueued) {
                logger.warn("Redis入队失败，回退到内存队列");
                reimburseTaskQueue.offer(task);
            }
        } else {
            resultCache.put(request.getInvoiceId(), holder);
            reimburseTaskQueue.offer(task);
        }

        logger.info("报销申请已提交: taskId={}, invoiceId={}, priority={}, 队列={}",
                task.getTaskId(), request.getInvoiceId(), task.getPriority(), useRedis ? "Redis" : "内存");
        return task.getTaskId();
    }

    public InvoiceReimburseResponse getReimburseResult(String taskId, long timeoutMs) throws Exception {
        ReimburseResultHolder holder = resultCache.get(taskId);

        if (holder == null && useRedis) {
            String resultKey = RESULT_PREFIX + taskId;
            String resultJson = redisTemplate.opsForValue().get(resultKey);
            if (resultJson != null) {
                holder = ReimburseResultHolder.fromJson(resultJson, objectMapper);
                resultCache.put(taskId, holder);
            }
        }

        if (holder == null && !useRedis) {
            for (ReimburseResultHolder h : resultCache.values()) {
                if (h.result != null && taskId.equals(h.result.getReimburseId())) {
                    holder = h;
                    break;
                }
            }
        }

        if (holder == null) {
            throw new InvoiceException(404, "报销任务不存在");
        }
        return holder.waitForResult(timeoutMs);
    }

    private void startWorkerIfNeeded() {
        if (!workerRunning) {
            synchronized (this) {
                if (!workerRunning) {
                    workerRunning = true;
                    executorService.submit(this::runReimburseWorker);
                }
            }
        }
    }

    private void runReimburseWorker() {
        logger.info("报销审核Worker启动, 队列模式: {}", useRedis ? "Redis" : "内存");
        while (!Thread.currentThread().isInterrupted()) {
            try {
                ReimburseTask task = null;

                if (useRedis && redisQueueService != null) {
                    ReimburseTaskDTO dto = redisQueueService.bPop(QUEUE_NAME, ReimburseTaskDTO.class, 1000);
                    if (dto != null) {
                        task = new ReimburseTask(dto);
                    }
                } else {
                    task = reimburseTaskQueue.poll(100, TimeUnit.MILLISECONDS);
                }

                if (task != null) {
                    processTask(task);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("报销Worker处理异常", e);
            }
        }
        logger.info("报销审核Worker停止");
    }

    private void processTask(ReimburseTask task) {
        InvoiceReimburseRequest request = task.getRequest();
        String taskId = task.getTaskId();
        ReimburseResultHolder holder = resultCache.get(taskId);

        if (holder == null) {
            holder = new ReimburseResultHolder();
            resultCache.put(taskId, holder);
        }

        try {
            InvoiceReimburseResponse response = processApply(task);
            holder.setResult(response);

            if (useRedis) {
                saveResultToRedis(taskId, holder);
            }

            logger.info("报销任务完成: taskId={}, invoiceId={}", task.getTaskId(), request.getInvoiceId());
        } catch (Exception e) {
            holder.setError(e);
            if (useRedis) {
                saveResultToRedis(taskId, holder);
            }
            logger.error("报销任务失败: taskId={}", task.getTaskId(), e);
        }
    }

    private void saveResultToRedis(String taskId, ReimburseResultHolder holder) {
        try {
            String resultKey = RESULT_PREFIX + taskId;
            String json = holder.toJson(objectMapper);
            redisTemplate.opsForValue().set(resultKey, json, 24, TimeUnit.HOURS);
        } catch (Exception e) {
            logger.error("保存报销结果到Redis失败: taskId={}", taskId, e);
        }
    }

    public InvoiceReimburseResponse processApply(ReimburseTask task) {
        InvoiceReimburseRequest request = task.getRequest();
        Invoice invoice = invoiceIssueService.getById(request.getInvoiceId());
        String currentStatus = invoice.getInvoiceStatus();

        if (invoiceStatusService.isAlreadyReimbursed(currentStatus)) {
            logger.warn("发票已在报销流程中: invoiceId={}, status={}", request.getInvoiceId(), currentStatus);
            throw InvoiceException.alreadyReimbursed();
        }
        if (!invoiceStatusService.canReimburse(currentStatus)) {
            logger.warn("发票状态不可报销: invoiceId={}, status={}", request.getInvoiceId(), currentStatus);
            throw InvoiceException.invalidStatus();
        }

        BigDecimal reimburseAmount = request.getReimburseAmount() != null
                ? request.getReimburseAmount()
                : invoice.getTotalAmount();

        if (reimburseAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvoiceException(400, "报销金额必须大于0");
        }
        if (reimburseAmount.compareTo(invoice.getTotalAmount()) > 0) {
            throw new InvoiceException(400, "报销金额不能大于发票金额");
        }

        java.time.Instant now = DateTimeUtil.now();
        InvoiceReimburse reimburse = InvoiceReimburse.builder()
                .reimburseId(task.getTaskId())
                .invoiceId(request.getInvoiceId())
                .reimburseUser(request.getReimburseUser())
                .reimburseDepartment(request.getReimburseDepartment())
                .reimburseAmount(reimburseAmount)
                .reimburseReason(request.getReimburseReason())
                .reimburseStatus(ReimburseStatusEnum.PENDING.getCode())
                .applyTime(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
        reimburseMapper.insert(reimburse);

        invoiceStatusService.reimbursePending(request.getInvoiceId(), request.getOperator());
        invoiceStatisticsService.recordReimburse(false);
        invoiceHistoryService.recordReimburseApply(request.getInvoiceId(), request.getOperator());

        logger.info("报销申请提交: reimburseId={}, invoiceId={}, user={}, priority={}",
                reimburse.getReimburseId(), request.getInvoiceId(), request.getReimburseUser(), task.getPriority());

        return InvoiceReimburseResponse.builder()
                .reimburseId(reimburse.getReimburseId())
                .invoiceId(reimburse.getInvoiceId())
                .status(ReimburseStatusEnum.PENDING.getCode())
                .reimburseUser(reimburse.getReimburseUser())
                .reimburseAmount(reimburse.getReimburseAmount())
                .applyTime(DateTimeUtil.formatFull(reimburse.getApplyTime()))
                .build();
    }

    public InvoiceReimburse approve(String reimburseId, String approver, String remark) {
        InvoiceReimburse reimburse = getById(reimburseId);
        if (!ReimburseStatusEnum.PENDING.getCode().equals(reimburse.getReimburseStatus())) {
            throw new InvoiceException(400, "报销申请已处理，无法重复审核");
        }
        reimburseMapper.updateStatus(reimburseId, ReimburseStatusEnum.APPROVED.getCode(), approver, remark);
        reimburse.setReimburseStatus(ReimburseStatusEnum.APPROVED.getCode());
        reimburse.setApprover(approver);
        reimburse.setApproveRemark(remark);
        reimburse.setApproveTime(DateTimeUtil.now());

        invoiceStatusService.reimbursed(reimburse.getInvoiceId(), approver);
        invoiceStatisticsService.recordReimburse(true);
        invoiceHistoryService.recordReimburseApprove(reimburse.getInvoiceId(), approver);

        logger.info("报销审核通过: reimburseId={}, approver={}", reimburseId, approver);
        return reimburse;
    }

    public InvoiceReimburse reject(String reimburseId, String approver, String reason) {
        InvoiceReimburse reimburse = getById(reimburseId);
        if (!ReimburseStatusEnum.PENDING.getCode().equals(reimburse.getReimburseStatus())) {
            throw new InvoiceException(400, "报销申请已处理，无法重复审核");
        }
        reimburseMapper.updateStatus(reimburseId, ReimburseStatusEnum.REJECTED.getCode(), approver, reason);
        reimburse.setReimburseStatus(ReimburseStatusEnum.REJECTED.getCode());
        reimburse.setApprover(approver);
        reimburse.setApproveRemark(reason);
        reimburse.setApproveTime(DateTimeUtil.now());

        invoiceHistoryService.recordReimburseReject(reimburse.getInvoiceId(), reason, approver);

        logger.info("报销审核拒绝: reimburseId={}, reason={}", reimburseId, reason);
        return reimburse;
    }

    public InvoiceReimburse getById(String reimburseId) {
        InvoiceReimburse reimburse = reimburseMapper.findById(reimburseId);
        if (reimburse == null) {
            throw new InvoiceException(404, "报销记录不存在");
        }
        return reimburse;
    }

    public List<InvoiceReimburse> getPendingSortedByPriority() {
        List<InvoiceReimburse> pending = reimburseMapper.findByStatus(ReimburseStatusEnum.PENDING.getCode());
        pending.sort(Comparator.comparingInt((InvoiceReimburse r) -> {
            String reason = r.getReimburseReason();
            if (reason != null) {
                String lower = reason.toLowerCase();
                if (lower.contains("紧急") || lower.contains("urgent")
                        || lower.contains("医疗") || lower.contains("medical")) {
                    return PRIORITY_URGENT;
                }
                if (lower.contains("重要") || lower.contains("important")) {
                    return PRIORITY_HIGH;
                }
            }
            return PRIORITY_NORMAL;
        }).reversed().thenComparing(InvoiceReimburse::getApplyTime));
        return pending;
    }

    public PriorityBlockingQueue<ReimburseTask> getReimburseTaskQueue() { return reimburseTaskQueue; }
    public ConcurrentHashMap<String, ReimburseResultHolder> getResultCache() { return resultCache; }
    public boolean isWorkerRunning() { return workerRunning; }
    public long getRedisQueueSize() {
        return useRedis && redisQueueService != null ? redisQueueService.size(QUEUE_NAME) : 0;
    }

    public List<InvoiceReimburse> getByInvoice(String invoiceId) { return reimburseMapper.findByInvoiceId(invoiceId); }
    public List<InvoiceReimburse> getByUser(String reimburseUser) { return reimburseMapper.findByUser(reimburseUser); }
}
