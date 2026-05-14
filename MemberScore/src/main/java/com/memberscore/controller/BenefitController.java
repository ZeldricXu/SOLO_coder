package com.memberscore.controller;

import com.memberscore.dto.ApiResponse;
import com.memberscore.entity.BenefitRecord;
import com.memberscore.service.BenefitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/benefits")
@RequiredArgsConstructor
@Slf4j
public class BenefitController {
    
    private final BenefitService benefitService;
    
    @GetMapping("/member/{memberId}")
    public ResponseEntity<ApiResponse<List<BenefitRecord>>> getMemberBenefits(
            @PathVariable String memberId,
            @RequestParam(required = false, defaultValue = "false") boolean activeOnly) {
        try {
            List<BenefitRecord> benefits = activeOnly 
                    ? benefitService.getMemberActiveBenefits(memberId)
                    : benefitService.getMemberBenefits(memberId);
            
            return ResponseEntity.ok(ApiResponse.success(benefits));
        } catch (Exception e) {
            log.error("查询会员权益失败: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        }
    }
    
    @PostMapping("/{benefitId}/use")
    public ResponseEntity<ApiResponse<BenefitRecord>> useBenefit(
            @PathVariable String benefitId) {
        try {
            BenefitRecord benefit = benefitService.useBenefit(benefitId);
            return ResponseEntity.ok(ApiResponse.success("权益已使用", benefit));
        } catch (Exception e) {
            log.error("使用权益失败: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        }
    }
}
