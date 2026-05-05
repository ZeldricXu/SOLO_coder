package com.iotconnect.controller;

import com.iotconnect.dto.ApiResponse;
import com.iotconnect.dto.DataReportRequest;
import com.iotconnect.dto.DataReportResponse;
import com.iotconnect.service.DataCollectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

@RestController
@RequestMapping("/api/v1/devices")
public class DataCollectionController {

    private static final Logger logger = LoggerFactory.getLogger(DataCollectionController.class);

    private final DataCollectionService dataCollectionService;

    public DataCollectionController(DataCollectionService dataCollectionService) {
        this.dataCollectionService = dataCollectionService;
    }

    @PostMapping("/data")
    public ResponseEntity<ApiResponse<DataReportResponse>> reportData(
            @RequestHeader(value = "X-Auth-Token", required = false) String authToken,
            @Valid @RequestBody DataReportRequest request) {
        
        logger.debug("Data report request: deviceId={}, dataType={}, value={}",
                request.getDeviceId(), request.getDataType(), request.getValue());
        
        try {
            DataReportResponse response = dataCollectionService.collectData(request);
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid data report request: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(400, "Invalid request: " + e.getMessage()));
        } catch (RuntimeException e) {
            logger.error("Data report failed: {}", e.getMessage());
            if (e.getMessage().contains("not found")) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error(404, e.getMessage()));
            }
            if (e.getMessage().contains("not online")) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(ApiResponse.error(503, e.getMessage()));
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Data report failed: " + e.getMessage()));
        }
    }

    @PostMapping("/batch-data")
    public ResponseEntity<ApiResponse<Integer>> reportBatchData(
            @RequestHeader(value = "X-Auth-Token", required = false) String authToken,
            @RequestBody java.util.List<DataReportRequest> requests) {
        
        logger.debug("Batch data report request: count={}", requests.size());
        
        int successCount = 0;
        
        for (DataReportRequest request : requests) {
            try {
                dataCollectionService.collectData(request);
                successCount++;
            } catch (Exception e) {
                logger.warn("Batch data item failed: deviceId={}, error={}", 
                        request.getDeviceId(), e.getMessage());
            }
        }
        
        logger.info("Batch data report completed: total={}, success={}", 
                requests.size(), successCount);
        
        return ResponseEntity.ok(ApiResponse.success(successCount));
    }
}
