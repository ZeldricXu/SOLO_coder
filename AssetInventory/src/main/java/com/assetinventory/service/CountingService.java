package com.assetinventory.service;

import com.assetinventory.entity.Asset;
import com.assetinventory.entity.InventoryDifference;
import com.assetinventory.entity.InventoryRecord;
import com.assetinventory.entity.InventoryTask;
import com.assetinventory.repository.InventoryRecordRepository;
import com.assetinventory.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class CountingService {

    private final InventoryRecordRepository recordRepository;
    private final TaskService taskService;
    private final AssetService assetService;
    private final DifferenceService differenceService;
    private final StatisticsService statisticsService;
    private final HistoryService historyService;

    @Autowired
    public CountingService(InventoryRecordRepository recordRepository,
                           TaskService taskService,
                           AssetService assetService,
                           DifferenceService differenceService,
                           StatisticsService statisticsService,
                           HistoryService historyService) {
        this.recordRepository = recordRepository;
        this.taskService = taskService;
        this.assetService = assetService;
        this.differenceService = differenceService;
        this.statisticsService = statisticsService;
        this.historyService = historyService;
    }

    public InventoryRecord executeCounting(String taskId, String assetId, int countQuantity, String countLocation) {
        taskService.validateTaskPendingOrAssigned(taskId);
        InventoryTask task = taskService.getTaskByIdOrThrow(taskId);

        Asset asset = assetService.getAssetByIdOrThrow(assetId);

        Instant countTime = IdGenerator.now();
        String location = (countLocation != null && !countLocation.isEmpty()) ? countLocation : asset.getAssetLocation();

        InventoryRecord record = new InventoryRecord();
        record.setCountId(IdGenerator.generateCountId());
        record.setTaskId(taskId);
        record.setAssetId(assetId);
        record.setCountPerson(task.getAssignedPerson());
        record.setCountQuantity(countQuantity);
        record.setCountLocation(location);
        record.setCountTime(countTime);

        boolean quantityMatch = (countQuantity == asset.getAssetQuantity());
        boolean locationMatch = location.equals(asset.getAssetLocation());

        if (quantityMatch && locationMatch) {
            record.setCountStatus("normal");
            assetService.updateAssetStatus(assetId, "counted");
            assetService.updateLastCountedAt(assetId, countTime);
        } else {
            record.setCountStatus("difference");

            if (!quantityMatch) {
                differenceService.createDifference(
                        record.getCountId(),
                        assetId,
                        "quantity",
                        asset.getAssetQuantity(),
                        countQuantity
                );
            }

            if (!locationMatch) {
                differenceService.createDifference(
                        record.getCountId(),
                        assetId,
                        "location",
                        asset.getAssetQuantity(),
                        countQuantity
                );
            }
        }

        record = recordRepository.save(record);

        statisticsService.incrementCountCount();

        if ("difference".equals(record.getCountStatus())) {
            statisticsService.incrementDiffCount();
        }

        historyService.recordCountingHistory(record.getCountId(), "EXECUTE",
                "执行盘点: " + record.getCountId() + ", 状态: " + record.getCountStatus());

        return record;
    }

    public List<InventoryRecord> getAllRecords() {
        return recordRepository.findAll();
    }

    public List<InventoryRecord> getRecordsByTaskId(String taskId) {
        return recordRepository.findByTaskId(taskId);
    }

    public List<InventoryRecord> getRecordsByAssetId(String assetId) {
        return recordRepository.findByAssetId(assetId);
    }

    public List<InventoryRecord> getRecordsByStatus(String status) {
        return recordRepository.findByCountStatus(status);
    }

    public Optional<InventoryRecord> getRecordById(String countId) {
        return recordRepository.findByCountId(countId);
    }
}
