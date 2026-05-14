package com.stockmgmt.controller;

import com.stockmgmt.common.Result;
import com.stockmgmt.entity.StockBatch;
import com.stockmgmt.entity.StockLocation;
import com.stockmgmt.service.BatchService;
import com.stockmgmt.service.LocationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/stock")
public class BatchLocationController {

    private static final Logger logger = LoggerFactory.getLogger(BatchLocationController.class);

    @Autowired
    private BatchService batchService;

    @Autowired
    private LocationService locationService;

    @GetMapping("/batches/{batchId}")
    public Result<StockBatch> getBatchById(@PathVariable String batchId) {
        logger.info("查询批次详情，batchId: {}", batchId);
        StockBatch batch = batchService.getBatchById(batchId);
        return Result.success(batch);
    }

    @GetMapping("/batches/no/{batchNo}")
    public Result<StockBatch> getBatchByNo(@PathVariable String batchNo) {
        logger.info("根据批次号查询，batchNo: {}", batchNo);
        StockBatch batch = batchService.getBatchByNo(batchNo);
        return Result.success(batch);
    }

    @GetMapping("/batches/product/{productId}")
    public Result<List<StockBatch>> getBatchesByProductId(@PathVariable String productId) {
        logger.info("查询商品批次列表，productId: {}", productId);
        List<StockBatch> batches = batchService.getBatchesByProductId(productId);
        return Result.success(batches);
    }

    @GetMapping("/batches/available/{productId}")
    public Result<List<StockBatch>> getAvailableBatches(@PathVariable String productId) {
        logger.info("查询商品可用批次，productId: {}", productId);
        List<StockBatch> batches = batchService.getAvailableBatches(productId);
        return Result.success(batches);
    }

    @GetMapping("/batches/expiring")
    public Result<List<StockBatch>> getExpiringBatches(
            @RequestParam(required = false) LocalDate expireDate) {
        logger.info("查询即将过期批次");
        if (expireDate == null) {
            expireDate = LocalDate.now().plusDays(30);
        }
        List<StockBatch> batches = batchService.getExpiringBatches(expireDate);
        return Result.success(batches);
    }

    @GetMapping("/batches/warehouse/{warehouseId}")
    public Result<List<StockBatch>> getBatchesByWarehouse(@PathVariable String warehouseId) {
        logger.info("查询仓库批次列表，warehouseId: {}", warehouseId);
        List<StockBatch> batches = batchService.getBatchesByWarehouse(warehouseId);
        return Result.success(batches);
    }

    @PostMapping("/locations")
    public Result<StockLocation> createLocation(@RequestBody StockLocation location) {
        logger.info("创建库位，编码: {}", location.getLocationCode());
        StockLocation saved = locationService.createLocation(location);
        return Result.success(saved);
    }

    @GetMapping("/locations/{locationId}")
    public Result<StockLocation> getLocationById(@PathVariable String locationId) {
        logger.info("查询库位详情，locationId: {}", locationId);
        StockLocation location = locationService.getLocationById(locationId);
        return Result.success(location);
    }

    @GetMapping("/locations/code/{locationCode}")
    public Result<StockLocation> getLocationByCode(@PathVariable String locationCode) {
        logger.info("根据编码查询库位，locationCode: {}", locationCode);
        StockLocation location = locationService.getLocationByCode(locationCode);
        return Result.success(location);
    }

    @GetMapping("/locations/warehouse/{warehouseId}")
    public Result<List<StockLocation>> getLocationsByWarehouse(@PathVariable String warehouseId) {
        logger.info("查询仓库库位列表，warehouseId: {}", warehouseId);
        List<StockLocation> locations = locationService.getLocationsByWarehouse(warehouseId);
        return Result.success(locations);
    }

    @GetMapping("/locations/warehouse/{warehouseId}/active")
    public Result<List<StockLocation>> getActiveLocations(@PathVariable String warehouseId) {
        logger.info("查询仓库可用库位，warehouseId: {}", warehouseId);
        List<StockLocation> locations = locationService.getActiveLocations(warehouseId);
        return Result.success(locations);
    }

    @PutMapping("/locations/{locationId}")
    public Result<StockLocation> updateLocation(
            @PathVariable String locationId,
            @RequestBody StockLocation location) {
        logger.info("更新库位，locationId: {}", locationId);
        StockLocation updated = locationService.updateLocation(locationId, location);
        return Result.success(updated);
    }
}
