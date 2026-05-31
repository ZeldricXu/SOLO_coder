package com.apishield.dp.service;

import com.apishield.application.service.ApplicationService;
import com.apishield.dp.domain.DpQueryLog;
import com.apishield.dp.domain.PrivacyBudget;
import com.apishield.dp.dto.BudgetConsumptionRequest;
import com.apishield.dp.dto.DpQueryRequest;
import com.apishield.dp.dto.DpQueryResponse;
import java.util.List;

public interface DifferentialPrivacyService extends ApplicationService {
    DpQueryResponse executeQuery(DpQueryRequest request);
    
    PrivacyBudget createBudget(String userId, String dataSource, double totalEpsilon, 
                               double totalDelta, String resetPeriod, boolean autoReset);
    PrivacyBudget getBudget(String budgetId);
    PrivacyBudget getBudgetByUserAndDataSource(String userId, String dataSource);
    List<PrivacyBudget> getBudgetsByUser(String userId);
    boolean consumeBudget(BudgetConsumptionRequest request);
    PrivacyBudget resetBudget(String budgetId);
    
    DpQueryLog getQueryLog(String logId);
    List<DpQueryLog> getQueryLogsByUser(String userId, int page, int size);
    List<DpQueryLog> getQueryLogsByDataSource(String dataSource, int page, int size);
    
    double calculateNoise(String noiseType, double sensitivity, double epsilon, double delta);
    boolean hasSufficientBudget(String userId, String dataSource, double epsilon, double delta);
}
