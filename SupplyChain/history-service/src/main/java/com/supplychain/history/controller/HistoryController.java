package com.supplychain.history.controller;

import com.supplychain.common.dto.ResponseResult;
import com.supplychain.common.entity.HistoryRecord;
import com.supplychain.history.service.HistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "历史记录", description = "供应链历史记录查询接口")
@RestController
@RequestMapping("/api/history")
@RequiredArgsConstructor
public class HistoryController {

    private final HistoryService historyService;

    @Operation(summary = "记录历史")
    @PostMapping
    public ResponseResult<HistoryRecord> record(@RequestBody HistoryRecord record) {
        return ResponseResult.success(historyService.record(record));
    }

    @Operation(summary = "获取历史列表")
    @GetMapping
    public ResponseResult<List<HistoryRecord>> listRecords(
            @RequestParam(required = false) String recordType,
            @RequestParam(required = false) String relatedId,
            @RequestParam(required = false) String action) {
        return ResponseResult.success(historyService.listRecords(recordType, relatedId, action));
    }

    @Operation(summary = "获取历史详情")
    @GetMapping("/{recordId}")
    public ResponseResult<HistoryRecord> getRecord(@PathVariable String recordId) {
        return ResponseResult.success(historyService.getRecord(recordId));
    }

    @Operation(summary = "按类型获取历史")
    @GetMapping("/type/{recordType}")
    public ResponseResult<List<HistoryRecord>> getRecordsByType(@PathVariable String recordType) {
        return ResponseResult.success(historyService.getRecordsByType(recordType));
    }

    @Operation(summary = "获取采购历史")
    @GetMapping("/purchase/{orderId}")
    public ResponseResult<List<HistoryRecord>> getPurchaseHistory(@PathVariable String orderId) {
        return ResponseResult.success(historyService.getPurchaseHistory(orderId));
    }

    @Operation(summary = "获取库存历史")
    @GetMapping("/inventory/{itemId}")
    public ResponseResult<List<HistoryRecord>> getInventoryHistory(@PathVariable String itemId) {
        return ResponseResult.success(historyService.getInventoryHistory(itemId));
    }

    @Operation(summary = "获取物流历史")
    @GetMapping("/logistics/{orderId}")
    public ResponseResult<List<HistoryRecord>> getLogisticsHistory(@PathVariable String orderId) {
        return ResponseResult.success(historyService.getLogisticsHistory(orderId));
    }

    @Operation(summary = "记录采购历史")
    @PostMapping("/purchase")
    public ResponseResult<HistoryRecord> recordPurchase(@RequestBody Map<String, String> request) {
        return ResponseResult.success(historyService.recordPurchase(
            request.get("relatedId"),
            request.get("action"),
            request.get("operator"),
            request.get("detail")
        ));
    }

    @Operation(summary = "记录库存历史")
    @PostMapping("/inventory")
    public ResponseResult<HistoryRecord> recordInventory(@RequestBody Map<String, String> request) {
        return ResponseResult.success(historyService.recordInventory(
            request.get("relatedId"),
            request.get("action"),
            request.get("operator"),
            request.get("detail")
        ));
    }

    @Operation(summary = "记录物流历史")
    @PostMapping("/logistics")
    public ResponseResult<HistoryRecord> recordLogistics(@RequestBody Map<String, String> request) {
        return ResponseResult.success(historyService.recordLogistics(
            request.get("relatedId"),
            request.get("action"),
            request.get("operator"),
            request.get("detail")
        ));
    }
}
