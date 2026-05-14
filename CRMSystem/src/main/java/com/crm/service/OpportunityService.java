package com.crm.service;

import com.crm.common.IdGenerator;
import com.crm.dto.OpportunityFollowRequest;
import com.crm.dto.OpportunityRequest;
import com.crm.entity.Customer;
import com.crm.entity.Opportunity;
import com.crm.exception.BusinessException;
import com.crm.repository.OpportunityRepository;
import com.crm.strategy.OpportunityAlertStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class OpportunityService {

    @Autowired
    private OpportunityRepository opportunityRepository;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private AnalysisService analysisService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private OpportunityAlertStrategy opportunityAlertStrategy;

    @Autowired
    private ReminderService reminderService;

    @Transactional
    public Map<String, Object> createOpportunity(OpportunityRequest request) {
        Customer customer = customerService.getCustomerById(request.getCustomerId());

        if ("deal".equals(customer.getCustomerStatus())) {
            throw new BusinessException("客户已成交，无法创建新机会");
        }

        Opportunity opportunity = new Opportunity();
        opportunity.setOpportunityId(IdGenerator.generateOpportunityId());
        opportunity.setCustomerId(request.getCustomerId());
        opportunity.setSalesId(request.getSalesId() != null ? request.getSalesId() : "sales_001");
        opportunity.setOpportunityAmount(request.getOpportunityAmount());
        
        String stage = request.getOpportunityStage();
        if (stage == null) {
            stage = "initial";
        }
        opportunity.setOpportunityStage(stage);
        
        Integer prob = request.getOpportunityProb();
        if (prob == null) {
            prob = calculateProbability(stage);
        }
        opportunity.setOpportunityProb(prob);
        
        opportunity.setOpportunityStatus("following");

        Opportunity savedOpportunity = opportunityRepository.save(opportunity);

        customerService.incrementOpportunityCount(request.getCustomerId());
        analysisService.incrementOpportunityCount();

        historyService.recordHistory(
                request.getCustomerId(),
                "opportunity",
                savedOpportunity.getOpportunityId(),
                "create",
                "创建销售机会，金额：" + request.getOpportunityAmount(),
                opportunity.getSalesId()
        );

        Map<String, Object> result = new HashMap<>();
        result.put("opportunity_id", savedOpportunity.getOpportunityId());
        result.put("status", savedOpportunity.getOpportunityStatus());
        return result;
    }

    @Transactional
    public Map<String, Object> followOpportunity(OpportunityFollowRequest request) {
        Opportunity opportunity = getOpportunityById(request.getOpportunityId());

        if ("success".equals(opportunity.getOpportunityStatus())) {
            throw new BusinessException("机会已成交，无法跟进");
        }
        if ("failed".equals(opportunity.getOpportunityStatus())) {
            throw new BusinessException("机会已失败，无法跟进");
        }

        String action = request.getAction();
        if (action == null) {
            action = "progress";
        }

        if ("progress".equals(action)) {
            if (request.getOpportunityStage() != null) {
                opportunity.setOpportunityStage(request.getOpportunityStage());
                opportunity.setOpportunityProb(calculateProbability(request.getOpportunityStage()));
            }
            if (request.getOpportunityProb() != null) {
                opportunity.setOpportunityProb(request.getOpportunityProb());
            }
        } else if ("success".equals(action)) {
            opportunity.setOpportunityStatus("success");
            opportunity.setDealTime(LocalDateTime.now());
            
            customerService.updateCustomerStatus(opportunity.getCustomerId(), "deal");
            analysisService.addDealAmount(opportunity.getOpportunityAmount());
            
            historyService.recordHistory(
                    opportunity.getCustomerId(),
                    "opportunity",
                    opportunity.getOpportunityId(),
                    "success",
                    "销售机会成交，金额：" + opportunity.getOpportunityAmount(),
                    request.getSalesId()
            );
        } else if ("failed".equals(action)) {
            opportunity.setOpportunityStatus("failed");
            opportunity.setFailReason(request.getFailReason());
            
            analysisService.incrementFailCount();
            
            historyService.recordHistory(
                    opportunity.getCustomerId(),
                    "opportunity",
                    opportunity.getOpportunityId(),
                    "fail",
                    "销售机会失败，原因：" + request.getFailReason(),
                    request.getSalesId()
            );
        }

        Opportunity updated = opportunityRepository.save(opportunity);

        if ("progress".equals(action)) {
            historyService.recordHistory(
                    opportunity.getCustomerId(),
                    "opportunity",
                    opportunity.getOpportunityId(),
                    "follow",
                    "跟进销售机会，阶段：" + updated.getOpportunityStage(),
                    request.getSalesId()
            );
        }

        Map<String, Object> result = new HashMap<>();
        result.put("opportunity_id", updated.getOpportunityId());
        result.put("status", updated.getOpportunityStatus());
        result.put("stage", updated.getOpportunityStage());
        return result;
    }

    public Opportunity getOpportunityById(String opportunityId) {
        return opportunityRepository.findByOpportunityId(opportunityId)
                .orElseThrow(() -> new BusinessException("销售机会不存在"));
    }

    public List<Opportunity> getCustomerOpportunities(String customerId) {
        return opportunityRepository.findByCustomerId(customerId);
    }

    public List<Opportunity> getSalesOpportunities(String salesId) {
        return opportunityRepository.findBySalesId(salesId);
    }

    public List<Opportunity> getOpportunitiesByStatus(String status) {
        return opportunityRepository.findByOpportunityStatus(status);
    }

    public List<Opportunity> getAllOpportunities() {
        return opportunityRepository.findAll();
    }

    private Integer calculateProbability(String stage) {
        return switch (stage) {
            case "initial" -> 10;
            case "contact" -> 20;
            case "proposal" -> 40;
            case "negotiation" -> 60;
            case "closing" -> 80;
            default -> 10;
        };
    }

    public List<Opportunity> getStaleOpportunities() {
        return opportunityRepository.findByOpportunityStatus("following").stream()
                .filter(opportunityAlertStrategy::shouldAlert)
                .collect(Collectors.toList());
    }

    public void checkAndAlertStaleOpportunities() {
        List<Opportunity> staleOpportunities = getStaleOpportunities();
        for (Opportunity opportunity : staleOpportunities) {
            createOpportunityAlert(opportunity);
        }
    }

    private void createOpportunityAlert(Opportunity opportunity) {
        int threshold = opportunityAlertStrategy.getAlertThresholdDays(opportunity);
        reminderService.createReminder(
                opportunity.getCustomerId(),
                opportunity.getSalesId(),
                "opportunity_alert",
                LocalDateTime.now(),
                "机会预警：机会 " + opportunity.getOpportunityId() + 
                " 已超过 " + threshold + " 天未更新，请及时跟进"
        );
    }

    public boolean shouldAlert(Opportunity opportunity) {
        return opportunityAlertStrategy.shouldAlert(opportunity);
    }

    public int getAlertThresholdDays(Opportunity opportunity) {
        return opportunityAlertStrategy.getAlertThresholdDays(opportunity);
    }
}
