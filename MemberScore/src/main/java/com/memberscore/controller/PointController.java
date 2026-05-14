package com.memberscore.controller;

import com.memberscore.dto.ApiResponse;
import com.memberscore.dto.ConsumePointRequest;
import com.memberscore.dto.EarnPointRequest;
import com.memberscore.dto.PointOperationResponse;
import com.memberscore.service.PointService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/points")
@RequiredArgsConstructor
@Slf4j
public class PointController {
    
    private final PointService pointService;
    
    @PostMapping("/earn")
    public ResponseEntity<ApiResponse<PointOperationResponse>> earnPoints(
            @Valid @RequestBody EarnPointRequest request) {
        try {
            log.info("收到积分获取请求: memberId={}, pointSource={}", 
                    request.getMemberId(), request.getPointSource());
            
            PointOperationResponse response = pointService.earnPoints(request);
            
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (Exception e) {
            log.error("积分获取失败: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        }
    }
    
    @PostMapping("/consume")
    public ResponseEntity<ApiResponse<PointOperationResponse>> consumePoints(
            @Valid @RequestBody ConsumePointRequest request) {
        try {
            log.info("收到积分消费请求: memberId={}, consumeAmount={}, consumeType={}", 
                    request.getMemberId(), request.getConsumeAmount(), request.getConsumeType());
            
            PointOperationResponse response = pointService.consumePoints(request);
            
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (Exception e) {
            log.error("积分消费失败: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        }
    }
    
    @GetMapping("/balance/{memberId}")
    public ResponseEntity<ApiResponse<Integer>> getAvailablePoints(
            @PathVariable String memberId) {
        try {
            int balance = pointService.getAvailablePoints(memberId);
            return ResponseEntity.ok(ApiResponse.success(balance));
        } catch (Exception e) {
            log.error("查询积分余额失败: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        }
    }
}
