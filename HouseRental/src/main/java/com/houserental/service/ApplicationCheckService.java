package com.houserental.service;

import com.houserental.config.ApplicationCheckConfig;
import com.houserental.config.ApplicationCheckConfig.CheckRule;
import com.houserental.entity.House;
import com.houserental.entity.LeaseApplication;
import com.houserental.exception.HouseRentalException;
import com.houserental.repository.ApplicationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ApplicationCheckService {

    @Autowired
    private ApplicationCheckConfig checkConfig;

    @Autowired
    private ApplicationRepository applicationRepository;

    @Autowired
    private HouseService houseService;

    public Map<String, Object> checkApplication(String houseId, String tenantId) {
        Map<String, Object> result = new HashMap<>();
        result.put("passed", true);
        result.put("violations", new HashMap<String, String>());

        List<CheckRule> enabledRules = checkConfig.getEnabledRules();
        
        for (CheckRule rule : enabledRules) {
            String ruleName = rule.getName();
            String ruleScope = rule.getScope();
            
            CheckResult checkResult = executeRule(rule, houseId, tenantId);
            
            if (!checkResult.passed) {
                result.put("passed", false);
                @SuppressWarnings("unchecked")
                Map<String, String> violations = (Map<String, String>) result.get("violations");
                violations.put(ruleName, checkResult.message);
            }
        }
        
        return result;
    }

    private CheckResult executeRule(CheckRule rule, String houseId, String tenantId) {
        return switch (rule.getName()) {
            case "single_house_duplicate_check" -> checkHouseDuplicateApplication(houseId, tenantId);
            case "tenant_history_check" -> checkTenantApplicationHistory(houseId, tenantId);
            case "house_status_check" -> checkHouseStatus(houseId);
            default -> new CheckResult(true, "规则未实现");
        };
    }

    private CheckResult checkHouseDuplicateApplication(String houseId, String tenantId) {
        List<LeaseApplication> pendingApplications = 
                applicationRepository.findByHouseIdAndApplicationStatus(houseId, "pending");
        
        for (LeaseApplication app : pendingApplications) {
            if (app.getTenantId().equals(tenantId)) {
                return new CheckResult(false, "您已对此房源提交过申请，请勿重复申请");
            }
        }
        
        return new CheckResult(true, "房源重复申请检查通过");
    }

    private CheckResult checkTenantApplicationHistory(String houseId, String tenantId) {
        List<LeaseApplication> tenantApplications = 
                applicationRepository.findByTenantId(tenantId);
        
        boolean hasOtherApplications = tenantApplications.stream()
                .anyMatch(app -> !app.getHouseId().equals(houseId));
        
        if (hasOtherApplications) {
            return new CheckResult(true, "租客有其他房源申请记录，同一租客可申请不同房源");
        }
        
        return new CheckResult(true, "租客历史检查通过");
    }

    private CheckResult checkHouseStatus(String houseId) {
        try {
            houseService.validateHouseAvailable(houseId);
            return new CheckResult(true, "房源状态检查通过");
        } catch (HouseRentalException e) {
            return new CheckResult(false, e.getMessage());
        }
    }

    public void validateApplication(String houseId, String tenantId) {
        Map<String, Object> checkResult = checkApplication(houseId, tenantId);
        if (!(boolean) checkResult.get("passed")) {
            @SuppressWarnings("unchecked")
            Map<String, String> violations = (Map<String, String>) checkResult.get("violations");
            String firstViolation = violations.values().stream().findFirst().orElse("申请检查失败");
            throw new HouseRentalException(400, firstViolation);
        }
    }

    public List<CheckRule> getEnabledRules() {
        return checkConfig.getEnabledRules();
    }

    public List<CheckRule> getRulesByScope(String scope) {
        return checkConfig.getRulesByScope(scope);
    }

    public boolean isRuleEnabled(String ruleName) {
        return checkConfig.isRuleEnabled(ruleName);
    }

    private static class CheckResult {
        boolean passed;
        String message;

        CheckResult(boolean passed, String message) {
            this.passed = passed;
            this.message = message;
        }
    }
}
