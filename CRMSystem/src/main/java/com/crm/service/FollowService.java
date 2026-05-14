package com.crm.service;

import com.crm.common.IdGenerator;
import com.crm.dto.FollowRequest;
import com.crm.entity.Category;
import com.crm.entity.Customer;
import com.crm.entity.Follow;
import com.crm.exception.BusinessException;
import com.crm.repository.CategoryRepository;
import com.crm.repository.CustomerCategoryRepository;
import com.crm.repository.FollowRepository;
import com.crm.strategy.ReminderTimeStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class FollowService {

    @Autowired
    private FollowRepository followRepository;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private AnalysisService analysisService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private ReminderService reminderService;

    @Autowired
    private ReminderTimeStrategy reminderTimeStrategy;

    @Autowired
    private CustomerCategoryRepository customerCategoryRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Transactional
    public Map<String, Object> createFollow(FollowRequest request) {
        Customer customer = customerService.getCustomerById(request.getCustomerId());

        if ("deal".equals(customer.getCustomerStatus())) {
            throw new BusinessException("客户已成交，无需跟进");
        }

        Follow follow = new Follow();
        follow.setFollowId(IdGenerator.generateFollowId());
        follow.setCustomerId(request.getCustomerId());
        follow.setSalesId(request.getSalesId() != null ? request.getSalesId() : "sales_001");
        
        String followType = request.getFollowType();
        if (followType == null) {
            followType = "phone";
        }
        follow.setFollowType(followType);
        
        follow.setFollowContent(request.getFollowContent());
        
        String followResult = request.getFollowResult();
        if (followResult == null) {
            followResult = "interested";
        }
        follow.setFollowResult(followResult);
        
        follow.setFollowTime(request.getFollowTime() != null ? request.getFollowTime() : LocalDateTime.now());
        follow.setNextFollow(request.getNextFollow());

        Follow savedFollow = followRepository.save(follow);

        customerService.incrementFollowCount(request.getCustomerId());
        analysisService.incrementFollowCount();

        if ("interested".equals(followResult)) {
            customerService.updateCustomerStatus(request.getCustomerId(), "interested");
        } else if ("deal".equals(followResult)) {
            customerService.updateCustomerStatus(request.getCustomerId(), "deal");
        } else if ("rejected".equals(followResult)) {
            customerService.updateCustomerStatus(request.getCustomerId(), "rejected");
        }

        if (request.getNextFollow() != null) {
            List<Category> customerCategories = getCustomerCategories(request.getCustomerId());
            LocalDateTime reminderTime = reminderTimeStrategy.calculateReminderTime(
                    customer, 
                    request.getNextFollow(), 
                    customerCategories
            );
            reminderService.createReminder(
                    request.getCustomerId(),
                    follow.getSalesId(),
                    "follow_remind",
                    reminderTime,
                    "请跟进客户：" + request.getCustomerId()
            );
        }

        historyService.recordHistory(
                request.getCustomerId(),
                "follow",
                savedFollow.getFollowId(),
                "create",
                "创建跟进记录：" + request.getFollowContent(),
                follow.getSalesId()
        );

        Map<String, Object> result = new HashMap<>();
        result.put("follow_id", savedFollow.getFollowId());
        result.put("result", savedFollow.getFollowResult());
        return result;
    }

    private List<Category> getCustomerCategories(String customerId) {
        return customerCategoryRepository.findByCustomerId(customerId).stream()
                .map(cc -> categoryRepository.findByCategoryId(cc.getCategoryId()))
                .filter(Optional -> Optional.isPresent())
                .map(Optional -> Optional.get())
                .collect(Collectors.toList());
    }

    public Follow getFollowById(String followId) {
        return followRepository.findByFollowId(followId)
                .orElseThrow(() -> new BusinessException("跟进记录不存在"));
    }

    public List<Follow> getCustomerFollows(String customerId) {
        return followRepository.findByCustomerId(customerId);
    }

    public List<Follow> getSalesFollows(String salesId) {
        return followRepository.findBySalesId(salesId);
    }

    public List<Follow> getAllFollows() {
        return followRepository.findAll();
    }
}
