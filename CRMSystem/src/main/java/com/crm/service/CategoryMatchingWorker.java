package com.crm.service;

import com.crm.config.CategoryQueueProperties;
import com.crm.entity.Category;
import com.crm.entity.CategoryTask;
import com.crm.entity.Customer;
import com.crm.entity.CustomerCategory;
import com.crm.exception.BusinessException;
import com.crm.repository.CategoryRepository;
import com.crm.repository.CustomerCategoryRepository;
import com.crm.repository.CustomerRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class CategoryMatchingWorker {

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private CustomerCategoryRepository customerCategoryRepository;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private RedisCategoryQueueService redisQueueService;

    @Autowired
    private CategoryQueueProperties queueProperties;

    private final AtomicInteger processedCount = new AtomicInteger(0);
    private final AtomicInteger failedCount = new AtomicInteger(0);
    private final AtomicInteger retryCount = new AtomicInteger(0);
    private final AtomicBoolean running = new AtomicBoolean(false);

    private Thread workerThread;

    @PostConstruct
    public void start() {
        running.set(true);
        workerThread = new Thread(this::runWorker, "category-matching-worker");
        workerThread.setDaemon(true);
        workerThread.start();
        log.info("分类匹配Worker已启动");
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (workerThread != null) {
            workerThread.interrupt();
        }
        log.info("分类匹配Worker已停止");
    }

    private void runWorker() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                CategoryTask task = redisQueueService.pollTask();
                if (task != null) {
                    processTask(task);
                }
            } catch (Exception e) {
                log.error("Worker处理任务时出错", e);
                try {
                    Thread.sleep(queueProperties.getPollIntervalMs());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    public void submitTask(String customerId, String customerValue) {
        redisQueueService.addTask(customerId, customerValue);
        log.debug("分类任务已提交: customerId={}, value={}", customerId, customerValue);
    }

    private void processTask(CategoryTask task) {
        log.debug("开始处理分类任务: taskId={}, customerId={}", task.getTaskId(), task.getCustomerId());

        try {
            processCustomerCategories(task.getCustomerId());
            redisQueueService.markTaskSuccess(task);
            processedCount.incrementAndGet();
            log.debug("分类任务处理成功: taskId={}, customerId={}", task.getTaskId(), task.getCustomerId());
        } catch (Exception e) {
            log.warn("分类任务处理失败: taskId={}, customerId={}, error={}", 
                    task.getTaskId(), task.getCustomerId(), e.getMessage());
            boolean willRetry = redisQueueService.markTaskFailed(task, e.getMessage());
            if (willRetry) {
                retryCount.incrementAndGet();
            } else {
                failedCount.incrementAndGet();
                log.error("分类任务最终失败: taskId={}, customerId={}", task.getTaskId(), task.getCustomerId());
            }
        }
    }

    private void processCustomerCategories(String customerId) {
        Optional<Customer> customerOpt = customerRepository.findByCustomerId(customerId);
        if (customerOpt.isEmpty()) {
            throw new BusinessException("客户不存在: " + customerId);
        }

        Customer customer = customerOpt.get();
        List<Category> categories = categoryRepository.findByCategoryStatus("active");

        for (Category category : categories) {
            if (shouldMatchCategory(customer, category)) {
                assignCategoryToCustomer(customerId, category.getCategoryId());
            }
        }
    }

    private boolean shouldMatchCategory(Customer customer, Category category) {
        if (category == null) {
            return false;
        }

        String categoryName = category.getCategoryName();
        String categoryType = category.getCategoryType();
        String categoryCode = category.getCategoryCode();

        if ("value".equals(categoryType)) {
            if ("VIP客户".equals(categoryName) || "vip".equalsIgnoreCase(categoryName) || "VIP".equalsIgnoreCase(categoryCode)) {
                return isVIPPotential(customer);
            }
            if ("普通客户".equals(categoryName) || "regular".equalsIgnoreCase(categoryCode)) {
                return !isVIPPotential(customer);
            }
        }

        if (categoryName != null && customer.getCustomerType() != null) {
            String customerType = customer.getCustomerType().toLowerCase();
            String catName = categoryName.toLowerCase();
            if (customerType.contains(catName) || catName.contains(customerType)) {
                return true;
            }
        }

        return false;
    }

    private boolean isVIPPotential(Customer customer) {
        int score = 0;
        if ("deal".equals(customer.getCustomerStatus())) score += 30;
        if ("closed".equals(customer.getCustomerStatus())) score += 30;
        if ("interested".equals(customer.getCustomerStatus())) score += 20;
        if (customer.getFollowCount() != null && customer.getFollowCount() >= 5) score += 20;
        if (customer.getOpportunityCount() != null && customer.getOpportunityCount() >= 2) score += 20;
        if ("enterprise".equalsIgnoreCase(customer.getCustomerType())) score += 10;
        if ("vip".equalsIgnoreCase(customer.getCustomerType())) score += 40;
        
        return score >= 50;
    }

    private void assignCategoryToCustomer(String customerId, String categoryId) {
        List<CustomerCategory> existing = customerCategoryRepository.findByCustomerId(customerId);
        boolean alreadyAssigned = existing.stream()
                .anyMatch(cc -> cc.getCategoryId().equals(categoryId));
        
        if (!alreadyAssigned) {
            CustomerCategory customerCategory = new CustomerCategory();
            customerCategory.setCustomerId(customerId);
            customerCategory.setCategoryId(categoryId);
            customerCategoryRepository.save(customerCategory);
            
            historyService.recordHistory(
                    customerId,
                    "category",
                    categoryId,
                    "auto_assign",
                    "自动分类匹配",
                    "system"
            );
        }
    }

    public int getProcessedCount() {
        return processedCount.get();
    }

    public int getFailedCount() {
        return failedCount.get();
    }

    public int getRetryCount() {
        return retryCount.get();
    }

    public void resetCounters() {
        processedCount.set(0);
        failedCount.set(0);
        retryCount.set(0);
    }

    public long getPendingTaskCount() {
        return redisQueueService.getPendingTaskCount();
    }

    public long getProcessingTaskCount() {
        return redisQueueService.getProcessingTaskCount();
    }

    public long getFailedTaskCount() {
        return redisQueueService.getFailedTaskCount();
    }

    public boolean requeueFailedTasks() {
        return redisQueueService.requeueFailedTasks();
    }
}
