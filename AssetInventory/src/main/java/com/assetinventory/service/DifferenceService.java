package com.assetinventory.service;

import com.assetinventory.config.AlertConfig;
import com.assetinventory.dto.DifferenceAlertResponse;
import com.assetinventory.dto.ProcessDifferenceRequest;
import com.assetinventory.entity.Asset;
import com.assetinventory.entity.InventoryDifference;
import com.assetinventory.exception.InventoryException;
import com.assetinventory.repository.InventoryDifferenceRepository;
import com.assetinventory.util.AsyncDifferenceDetector;
import com.assetinventory.util.DifferenceAlertManager;
import com.assetinventory.util.DifferenceAlertManager.AlertRecord;
import com.assetinventory.util.DifferenceAlertManager.SeverityLevel;
import com.assetinventory.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class DifferenceService {

    private static final Logger logger = LoggerFactory.getLogger(DifferenceService.class);

    private final InventoryDifferenceRepository diffRepository;
    private final AssetService assetService;
    private final AsyncDifferenceDetector asyncDifferenceDetector;
    private final DifferenceAlertManager differenceAlertManager;
    private final StatisticsService statisticsService;
    private final AlertConfig alertConfig;

    @Autowired
    public DifferenceService(InventoryDifferenceRepository diffRepository,
                             AssetService assetService,
                             AsyncDifferenceDetector asyncDifferenceDetector,
                             DifferenceAlertManager differenceAlertManager,
                             StatisticsService statisticsService,
                             AlertConfig alertConfig) {
        this.diffRepository = diffRepository;
        this.assetService = assetService;
        this.asyncDifferenceDetector = asyncDifferenceDetector;
        this.differenceAlertManager = differenceAlertManager;
        this.statisticsService = statisticsService;
        this.alertConfig = alertConfig;
    }

    public InventoryDifference createDifference(String planId, String taskId, String assetId,
                                                 int systemCount, int actualCount) {
        String diffType = determineDiffType(systemCount, actualCount);
        int diffValue = calculateDiffValue(systemCount, actualCount);

        InventoryDifference diff = new InventoryDifference();
        diff.setDiffId(IdGenerator.generateDifferenceId());
        diff.setPlanId(planId);
        diff.setTaskId(taskId);
        diff.setAssetId(assetId);
        diff.setDiffType(diffType);
        diff.setDiffSystem(systemCount);
        diff.setDiffActual(actualCount);
        diff.setDiffValue(diffValue);
        diff.setDiffStatus("pending");
        diff.setCreatedAt(IdGenerator.now());

        diff = diffRepository.save(diff);

        statisticsService.incrementDiffCount();

        logger.info("Created difference: {}, type: {}, value: {}",
                diff.getDiffId(), diffType, diffValue);

        return diff;
    }

    private String determineDiffType(int systemCount, int actualCount) {
        if (actualCount > systemCount) {
            return "盘盈";
        } else if (actualCount < systemCount) {
            return "盘亏";
        } else {
            return "一致";
        }
    }

    private int calculateDiffValue(int systemCount, int actualCount) {
        return actualCount - systemCount;
    }

    public void startAsyncDetection(String planId, String taskId) {
        asyncDifferenceDetector.submitTask(planId, taskId);
        logger.info("Started async detection for plan: {}, task: {}", planId, taskId);
    }

    public List<InventoryDifference> getAllDifferences() {
        return diffRepository.findAll();
    }

    public List<InventoryDifference> getDifferencesByStatus(String status) {
        return diffRepository.findByDiffStatus(status);
    }

    public List<InventoryDifference> getDifferencesByType(String diffType) {
        return diffRepository.findByDiffType(diffType);
    }

    public List<InventoryDifference> getDifferencesByPlanId(String planId) {
        return diffRepository.findByPlanId(planId);
    }

    public List<InventoryDifference> getDifferencesByTaskId(String taskId) {
        return diffRepository.findByTaskId(taskId);
    }

    public Optional<InventoryDifference> getDifferenceById(String diffId) {
        return diffRepository.findByDiffId(diffId);
    }

    public InventoryDifference getDifferenceByIdOrThrow(String diffId) {
        return diffRepository.findByDiffId(diffId)
                .orElseThrow(() -> new InventoryException(404, "差异不存在: " + diffId));
    }

    public void validateDifferencePending(String diffId) {
        InventoryDifference diff = getDifferenceByIdOrThrow(diffId);
        if (!"pending".equals(diff.getDiffStatus())) {
            throw new InventoryException(400, "差异已处理，无法重复处理");
        }
    }

    public InventoryDifference processDifference(String diffId, ProcessDifferenceRequest request) {
        InventoryDifference diff = getDifferenceByIdOrThrow(diffId);
        validateDifferencePending(diffId);

        diff.setDiffStatus(request.getAction());
        diff.setHandledBy(request.getHandler());
        diff.setHandledReason(request.getReason());
        diff.setHandledAt(IdGenerator.now());

        if ("adjust".equals(request.getAction())) {
            assetService.adjustAssetCount(diff.getAssetId(), diff.getDiffValue());
        }

        diff = diffRepository.save(diff);

        differenceAlertManager.clearAlert(diffId);

        logger.info("Processed difference: {}, action: {}", diffId, request.getAction());

        return diff;
    }

    public DifferenceAlertResponse checkAndSendAlert(String diffId) {
        InventoryDifference diff = getDifferenceByIdOrThrow(diffId);

        boolean shouldTrigger = differenceAlertManager.shouldTriggerAlert(diff);
        if (!shouldTrigger) {
            return DifferenceAlertResponse.notTriggered(diffId);
        }

        SeverityLevel severity = differenceAlertManager.determineSeverity(diff);
        AlertRecord record = differenceAlertManager.triggerAlert(diff);

        if (record == null) {
            return DifferenceAlertResponse.notTriggered(diffId);
        }

        statisticsService.incrementAlertCount();

        return DifferenceAlertResponse.triggered(
                diffId,
                severity.getConfigKey(),
                record.getSeverityName(),
                record.getMessage(),
                record.getAlertCount(),
                record.getAlertIntervalMs()
        );
    }

    public DifferenceAlertResponse getAlertInfo(String diffId) {
        InventoryDifference diff = getDifferenceByIdOrThrow(diffId);
        SeverityLevel severity = differenceAlertManager.determineSeverity(diff);

        return DifferenceAlertResponse.info(
                diffId,
                severity.getConfigKey(),
                differenceAlertManager.getNameForSeverity(severity),
                differenceAlertManager.getAlertIntervalForSeverity(severity),
                differenceAlertManager.getThresholdForSeverity(severity),
                differenceAlertManager.getLevelForSeverity(severity)
        );
    }

    public List<AlertRecord> getSentAlerts() {
        return differenceAlertManager.getSentAlerts();
    }

    public List<AlertRecord> getSentAlertsBySeverity(String severityKey) {
        return differenceAlertManager.getSentAlertsBySeverityKey(severityKey);
    }

    public int getSentAlertCount() {
        return differenceAlertManager.getSentAlertCount();
    }

    public int getSentAlertCountBySeverity(String severityKey) {
        SeverityLevel severity = SeverityLevel.fromString(severityKey);
        return differenceAlertManager.getSentAlertCountBySeverity(severity);
    }

    public void clearAlert(String diffId) {
        differenceAlertManager.clearAlert(diffId);
    }

    public void clearAllAlerts() {
        differenceAlertManager.clearAllAlerts();
    }

    public boolean isAlertEnabled() {
        return differenceAlertManager.isEnabled();
    }

    public List<String> getAvailableSeverities() {
        return differenceAlertManager.getAvailableSeverities();
    }

    public void resetAlertManager() {
        differenceAlertManager.reset();
    }

    public void shutdown() {
        asyncDifferenceDetector.shutdown();
    }
}
