package com.assetmanage.service;

import com.assetmanage.common.IdGenerator;
import com.assetmanage.dto.InventoryDiffHandleRequest;
import com.assetmanage.entity.Asset;
import com.assetmanage.entity.InventoryCheck;
import com.assetmanage.entity.InventoryDifference;
import com.assetmanage.exception.BusinessException;
import com.assetmanage.repository.InventoryCheckRepository;
import com.assetmanage.repository.InventoryDifferenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryCheckRepository checkRepository;
    private final InventoryDifferenceRepository diffRepository;
    private final AssetService assetService;
    private final HistoryService historyService;

    @Transactional
    public String createInventoryCheck(String type, String department, int totalAssets) {
        InventoryCheck check = new InventoryCheck();
        check.setCheckId(IdGenerator.generateCheckId());
        check.setCheckType(type);
        check.setCheckDepartment(department);
        check.setCheckStatus("in_progress");
        check.setTotalAssets(totalAssets);
        check.setCheckedAssets(0);
        check.setMatchedAssets(0);
        check.setDiffAssets(0);
        checkRepository.save(check);
        log.info("盘点任务创建成功: checkId={}", check.getCheckId());
        return check.getCheckId();
    }

    @Transactional
    public String createDifference(String checkId, String assetId, String systemLocation, 
                                   String actualLocation, String diffType) {
        InventoryDifference diff = new InventoryDifference();
        diff.setDiffId(IdGenerator.generateDiffId());
        diff.setCheckId(checkId);
        diff.setAssetId(assetId);
        diff.setSystemLocation(systemLocation);
        diff.setActualLocation(actualLocation);
        diff.setDiffType(diffType);
        diff.setDiffStatus("pending");
        diffRepository.save(diff);

        InventoryCheck check = getCheckById(checkId);
        check.setDiffAssets(check.getDiffAssets() + 1);
        checkRepository.save(check);

        log.info("盘点差异创建成功: diffId={}, checkId={}, assetId={}", 
                diff.getDiffId(), checkId, assetId);
        return diff.getDiffId();
    }

    @Transactional
    public void handleDifference(InventoryDiffHandleRequest request) {
        InventoryDifference diff = getDifferenceById(request.getDiffId());

        if ("handled".equals(diff.getDiffStatus())) {
            throw new BusinessException("该差异已处理");
        }

        Asset asset = assetService.getAssetById(diff.getAssetId());
        String diffType = diff.getDiffType();

        switch (diffType) {
            case "location_diff":
                asset.setLocation(diff.getActualLocation());
                assetService.save(asset);
                historyService.recordHistory(diff.getAssetId(), "inventory",
                        "盘点位置调整: " + diff.getSystemLocation() + " -> " + diff.getActualLocation(),
                        request.getOperatorId());
                break;
            case "status_diff":
                historyService.recordHistory(diff.getAssetId(), "inventory",
                        "盘点状态差异处理", request.getOperatorId());
                break;
            case "quantity_diff":
                historyService.recordHistory(diff.getAssetId(), "inventory",
                        "盘点数量差异处理", request.getOperatorId());
                break;
            default:
                log.warn("未知差异类型: {}", diffType);
        }

        diff.setDiffStatus("handled");
        diff.setHandledAt(LocalDateTime.now());
        diffRepository.save(diff);

        InventoryCheck check = getCheckById(diff.getCheckId());
        check.setMatchedAssets(check.getMatchedAssets() + 1);
        check.setDiffAssets(check.getDiffAssets() - 1);

        List<InventoryDifference> pendingDiffs = diffRepository.findByCheckIdAndDiffStatus(diff.getCheckId(), "pending");
        if (pendingDiffs.isEmpty()) {
            check.setCheckStatus("completed");
            check.setCheckedAt(LocalDateTime.now());
        }
        checkRepository.save(check);

        log.info("盘点差异处理成功: diffId={}", request.getDiffId());
    }

    public InventoryCheck getCheckById(String checkId) {
        return checkRepository.findById(checkId)
                .orElseThrow(() -> new BusinessException("盘点任务不存在: " + checkId));
    }

    public InventoryDifference getDifferenceById(String diffId) {
        return diffRepository.findById(diffId)
                .orElseThrow(() -> new BusinessException("盘点差异不存在: " + diffId));
    }

    public List<InventoryCheck> getAllChecks() {
        return checkRepository.findAllByOrderByCreatedAtDesc();
    }

    public List<InventoryCheck> getChecksByStatus(String status) {
        return checkRepository.findByCheckStatus(status);
    }

    public List<InventoryDifference> getDifferencesByCheck(String checkId) {
        return diffRepository.findByCheckId(checkId);
    }

    public List<InventoryDifference> getPendingDifferences() {
        return diffRepository.findByDiffStatus("pending");
    }

    @Transactional
    public void completeCheck(String checkId) {
        InventoryCheck check = getCheckById(checkId);
        check.setCheckStatus("completed");
        check.setCheckedAt(LocalDateTime.now());
        checkRepository.save(check);
        log.info("盘点任务完成: checkId={}", checkId);
    }
}
