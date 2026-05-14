package com.supplychain.inventory.controller;

import com.supplychain.common.dto.InventorySyncRequest;
import com.supplychain.common.dto.ResponseResult;
import com.supplychain.common.entity.Inventory;
import com.supplychain.common.entity.InventorySync;
import com.supplychain.common.entity.InventoryWarning;
import com.supplychain.inventory.service.InventoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "库存协同管理", description = "库存同步与预警管理接口")
@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    @Operation(summary = "库存同步")
    @PostMapping("/sync")
    public ResponseResult<Map<String, Object>> syncInventory(@RequestBody InventorySyncRequest request) {
        InventorySync sync = inventoryService.syncInventory(request);
        return ResponseResult.success(Map.of(
            "sync_id", sync.getSyncId(),
            "status", "synced"
        ));
    }

    @Operation(summary = "获取库存列表")
    @GetMapping
    public ResponseResult<List<Inventory>> listInventories(
            @RequestParam(required = false) String supplierId,
            @RequestParam(required = false) String itemId) {
        return ResponseResult.success(inventoryService.listInventories(supplierId, itemId));
    }

    @Operation(summary = "获取预警列表")
    @GetMapping("/warnings")
    public ResponseResult<List<InventoryWarning>> listWarnings(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type) {
        return ResponseResult.success(inventoryService.listWarnings(status, type));
    }

    @Operation(summary = "处理预警")
    @PostMapping("/warnings/{warningId}/handle")
    public ResponseResult<InventoryWarning> handleWarning(
            @PathVariable String warningId,
            @RequestBody Map<String, String> request) {
        String handler = request.getOrDefault("handler", "system");
        return ResponseResult.success(inventoryService.handleWarning(warningId, handler));
    }

    @Operation(summary = "获取同步记录")
    @GetMapping("/syncs")
    public ResponseResult<List<InventorySync>> listSyncRecords(
            @RequestParam(required = false) String supplierId) {
        return ResponseResult.success(inventoryService.listSyncRecords(supplierId));
    }
}
