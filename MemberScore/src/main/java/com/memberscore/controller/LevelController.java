package com.memberscore.controller;

import com.memberscore.dto.ApiResponse;
import com.memberscore.dto.LevelQueryResponse;
import com.memberscore.entity.LevelConfig;
import com.memberscore.entity.Member;
import com.memberscore.service.LevelService;
import com.memberscore.service.MemberService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/levels")
@RequiredArgsConstructor
@Slf4j
public class LevelController {
    
    private final LevelService levelService;
    private final MemberService memberService;
    
    @GetMapping("/query")
    public ResponseEntity<ApiResponse<LevelQueryResponse>> queryMemberLevel(
            @RequestParam String memberId) {
        try {
            log.info("查询会员等级: memberId={}", memberId);
            
            Member member = memberService.getMemberByMemberId(memberId)
                    .orElseThrow(() -> new RuntimeException("会员不存在: " + memberId));
            
            LevelQueryResponse response = levelService.queryMemberLevel(member);
            
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (Exception e) {
            log.error("查询会员等级失败: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        }
    }
    
    @GetMapping("/configs")
    public ResponseEntity<ApiResponse<List<LevelConfig>>> getAllLevelConfigs() {
        try {
            List<LevelConfig> configs = levelService.getAllEnabledLevels();
            return ResponseEntity.ok(ApiResponse.success(configs));
        } catch (Exception e) {
            log.error("查询等级配置失败: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error(500, e.getMessage()));
        }
    }
}
