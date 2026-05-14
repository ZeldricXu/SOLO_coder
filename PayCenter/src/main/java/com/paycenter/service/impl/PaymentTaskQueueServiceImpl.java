package com.paycenter.service.impl;

import com.alibaba.fastjson.JSON;
import com.paycenter.dto.PaymentRequest;
import com.paycenter.entity.MerchantConfig;
import com.paycenter.entity.PaymentChannel;
import com.paycenter.entity.PaymentTask;
import com.paycenter.entity.Transaction;
import com.paycenter.enums.ChannelType;
import com.paycenter.exception.BusinessException;
import com.paycenter.repository.PaymentTaskRepository;
import com.paycenter.service.MerchantConfigService;
import com.paycenter.service.PaymentTaskQueueService;
import com.paycenter.service.TransactionService;
import com.paycenter.util.IdGenerator;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class PaymentTaskQueueServiceImpl implements PaymentTaskQueueService {

    private static final Logger logger = LoggerFactory.getLogger(PaymentTaskQueueServiceImpl.class);

    private static final String REDIS_QUEUE_KEY = "paycenter:payment:tasks:queue";
    private static final String REDIS_PROCESSING_KEY = "paycenter:payment:tasks:processing";
    private static final String REDIS_FAILED_KEY = "paycenter:payment:tasks:failed";

    @Value("${payment.async.enabled:true}")
    private boolean asyncEnabled;

    @Value("${payment.async.worker-count:4}")
    private int workerCount;

    @Value("${payment.async.retry-interval:60000}")
    private int retryInterval;

    @Value("${payment.async.max-retry:3}")
    private int maxRetry;

    @Autowired
    private PaymentTaskRepository paymentTaskRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private MerchantConfigService merchantConfigService;

    @Autowired
    private TransactionService transactionService;

    private final ExecutorService workerExecutor = Executors.newFixedThreadPool(4);
    private final AtomicBoolean running = new AtomicBoolean(false);

    @PostConstruct
    public void init() {
        if (asyncEnabled) {
            logger.info("初始化支付任务异步队列服务，Worker数量: {}", workerCount);
            startWorkers();
            recoverFailedTasks();
        }
    }

    @Override
    public boolean isAsyncEnabled(MerchantConfig config) {
        return asyncEnabled;
    }

    @Override
    @Transactional
    public void submitPaymentTask(Transaction transaction, PaymentChannel channel, PaymentRequest request) {
        String taskId = IdGenerator.generateTransactionId() + "_task";
        
        PaymentTask task = PaymentTask.builder()
                .taskId(taskId)
                .transactionId(transaction.getTransactionId())
                .merchantId(transaction.getMerchantId())
                .orderNo(transaction.getOrderNo())
                .amount(transaction.getAmount())
                .channelId(channel.getChannelId())
                .channelType(channel.getChannelType())
                .status(PaymentTask.TaskStatus.PENDING)
                .retryCount(0)
                .maxRetryCount(maxRetry)
                .build();
        
        paymentTaskRepository.save(task);
        pushToRedisQueue(task);
        
        logger.info("支付任务已提交: taskId={}, transactionId={}, merchantId={}",
                taskId, transaction.getTransactionId(), transaction.getMerchantId());
    }

    private void pushToRedisQueue(PaymentTask task) {
        try {
            String taskJson = JSON.toJSONString(task);
            redisTemplate.opsForList().rightPush(REDIS_QUEUE_KEY, taskJson);
            logger.debug("任务已推送到Redis队列: taskId={}", task.getTaskId());
        } catch (Exception e) {
            logger.warn("Redis队列推送失败，任务仅保存到数据库: taskId={}", task.getTaskId(), e);
        }
    }

    @Override
    public PaymentTask getNextPendingTask() {
        try {
            String taskJson = redisTemplate.opsForList().leftPop(REDIS_QUEUE_KEY, 5, TimeUnit.SECONDS);
            if (taskJson != null) {
                PaymentTask task = JSON.parseObject(taskJson, PaymentTask.class);
                markTaskProcessing(task.getTaskId());
                return task;
            }
        } catch (Exception e) {
            logger.warn("从Redis获取任务失败，尝试从数据库获取", e);
        }
        
        List<PaymentTask> dbTasks = paymentTaskRepository.findPendingTasks(PaymentTask.TaskStatus.PENDING);
        if (!dbTasks.isEmpty()) {
            PaymentTask task = dbTasks.get(0);
            markTaskProcessing(task.getTaskId());
            return task;
        }
        
        return null;
    }

    @Override
    @Transactional
    public void markTaskProcessing(String taskId) {
        PaymentTask task = paymentTaskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException("任务不存在: " + taskId));
        
        task.setStatus(PaymentTask.TaskStatus.PROCESSING);
        task.setProcessedAt(LocalDateTime.now());
        paymentTaskRepository.save(task);
        
        logger.info("任务开始处理: taskId={}", taskId);
    }

    @Override
    @Transactional
    public void markTaskSuccess(String taskId, String result) {
        PaymentTask task = paymentTaskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException("任务不存在: " + taskId));
        
        task.setStatus(PaymentTask.TaskStatus.SUCCESS);
        task.setCompletedAt(LocalDateTime.now());
        paymentTaskRepository.save(task);
        
        logger.info("任务处理成功: taskId={}", taskId);
        
        try {
            redisTemplate.opsForHash().delete(REDIS_PROCESSING_KEY, taskId);
        } catch (Exception e) {
            logger.warn("清理Redis处理中标记失败", e);
        }
    }

    @Override
    @Transactional
    public void markTaskFailed(String taskId, String errorMessage) {
        PaymentTask task = paymentTaskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException("任务不存在: " + taskId));
        
        task.setStatus(PaymentTask.TaskStatus.FAILED);
        task.setErrorMessage(errorMessage);
        task.setCompletedAt(LocalDateTime.now());
        paymentTaskRepository.save(task);
        
        logger.error("任务处理失败: taskId={}, error={}", taskId, errorMessage);
        
        try {
            redisTemplate.opsForHash().delete(REDIS_PROCESSING_KEY, taskId);
            redisTemplate.opsForHash().put(REDIS_FAILED_KEY, taskId, errorMessage);
        } catch (Exception e) {
            logger.warn("更新Redis失败状态失败", e);
        }
    }

    @Override
    @Transactional
    public void markTaskRetry(String taskId, String errorMessage) {
        PaymentTask task = paymentTaskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException("任务不存在: " + taskId));
        
        task.setRetryCount(task.getRetryCount() + 1);
        task.setStatus(PaymentTask.TaskStatus.RETRY);
        task.setErrorMessage(errorMessage);
        task.setNextRetryAt(LocalDateTime.now().plusMillis(retryInterval));
        paymentTaskRepository.save(task);
        
        logger.warn("任务重试: taskId={}, retryCount={}, error={}", 
                taskId, task.getRetryCount(), errorMessage);
        
        if (task.getRetryCount() < task.getMaxRetryCount()) {
            try {
                redisTemplate.opsForList().rightPush(REDIS_QUEUE_KEY, JSON.toJSONString(task));
            } catch (Exception e) {
                logger.warn("Redis重试队列推送失败", e);
            }
        } else {
            logger.error("任务重试次数达到上限: taskId={}", taskId);
            markTaskFailed(taskId, errorMessage);
        }
    }

    @Override
    public List<PaymentTask> getRetryableTasks() {
        return paymentTaskRepository.findByStatusAndNextRetryAtBefore(
                PaymentTask.TaskStatus.RETRY, LocalDateTime.now());
    }

    @Override
    @Transactional
    public void recoverFailedTasks() {
        logger.info("开始恢复失败的支付任务...");
        
        List<PaymentTask.TaskStatus> statuses = Arrays.asList(
                PaymentTask.TaskStatus.PENDING,
                PaymentTask.TaskStatus.PROCESSING,
                PaymentTask.TaskStatus.RETRY
        );
        
        List<PaymentTask> stuckTasks = paymentTaskRepository.findByStatusIn(statuses);
        logger.info("发现 {} 个需要恢复的任务", stuckTasks.size());
        
        for (PaymentTask task : stuckTasks) {
            if (task.getStatus() == PaymentTask.TaskStatus.PROCESSING) {
                if (task.getProcessedAt() != null && 
                    task.getProcessedAt().isBefore(LocalDateTime.now().minusMinutes(5))) {
                    logger.warn("恢复超时处理中的任务: taskId={}", task.getTaskId());
                    markTaskRetry(task.getTaskId(), "任务处理超时，自动恢复重试");
                }
            } else if (task.getStatus() == PaymentTask.TaskStatus.RETRY) {
                if (task.getNextRetryAt() != null && 
                    task.getNextRetryAt().isBefore(LocalDateTime.now())) {
                    logger.warn("恢复待重试的任务: taskId={}", task.getTaskId());
                    pushToRedisQueue(task);
                }
            } else if (task.getStatus() == PaymentTask.TaskStatus.PENDING) {
                logger.warn("恢复待处理的任务: taskId={}", task.getTaskId());
                pushToRedisQueue(task);
            }
        }
    }

    @Override
    public long getPendingTaskCount() {
        try {
            Long redisCount = redisTemplate.opsForList().size(REDIS_QUEUE_KEY);
            if (redisCount != null && redisCount > 0) {
                return redisCount;
            }
        } catch (Exception e) {
            logger.warn("获取Redis队列长度失败", e);
        }
        
        List<PaymentTask> dbTasks = paymentTaskRepository.findPendingTasks(PaymentTask.TaskStatus.PENDING);
        return dbTasks.size();
    }

    private void startWorkers() {
        if (running.compareAndSet(false, true)) {
            logger.info("启动 {} 个支付任务Worker", workerCount);
            
            for (int i = 0; i < workerCount; i++) {
                final int workerId = i;
                workerExecutor.submit(() -> runWorker(workerId));
            }
        }
    }

    private void runWorker(int workerId) {
        logger.info("Worker {} 已启动", workerId);
        
        while (running.get()) {
            try {
                PaymentTask task = getNextPendingTask();
                if (task != null) {
                    processTask(task, workerId);
                }
            } catch (Exception e) {
                logger.error("Worker {} 处理异常", workerId, e);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        logger.info("Worker {} 已停止", workerId);
    }

    private void processTask(PaymentTask task, int workerId) {
        logger.debug("Worker {} 开始处理任务: taskId={}", workerId, task.getTaskId());
        
        try {
            executeChannelCall(task);
            markTaskSuccess(task.getTaskId(), "success");
        } catch (Exception e) {
            logger.error("Worker {} 处理任务失败: taskId={}", workerId, task.getTaskId(), e);
            markTaskRetry(task.getTaskId(), e.getMessage());
        }
    }

    private void executeChannelCall(PaymentTask task) {
        logger.info("执行渠道调用: taskId={}, channelId={}, amount={}",
                task.getTaskId(), task.getChannelId(), task.getAmount());
        
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("渠道调用被中断");
        }
    }

    @Override
    @Scheduled(fixedDelay = 30000)
    public void processTaskQueue() {
        if (!asyncEnabled) {
            return;
        }
        
        long pendingCount = getPendingTaskCount();
        if (pendingCount > 0) {
            logger.debug("当前待处理任务数: {}", pendingCount);
        }
    }

    @Scheduled(cron = "0 */5 * * * ?")
    public void scheduledRecovery() {
        if (asyncEnabled) {
            recoverFailedTasks();
        }
    }
}
