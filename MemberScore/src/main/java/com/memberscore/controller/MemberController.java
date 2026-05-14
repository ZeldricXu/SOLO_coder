package com.memberscore.controller;

import com.memberscore.dto.ApiResponse;
import com.memberscore.dto.MemberCreateRequest;
import com.memberscore.entity.Member;
import com.memberscore.service.MemberService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/members")
@RequiredArgsConstructor
@Slf4j
public class MemberController {
    
    private final MemberService memberService;
    
    @PostMapping
    public ResponseEntity<ApiResponse<Member>> createMember(
            @Valid @RequestBody MemberCreateRequest request) {
        try {
            log.info("创建会员请求: memberId={}, userId={}", 
                    request.getMemberId(), request.getUserId());
            
            Member member = memberService.createMember(request);
            
            return ResponseEntity.ok(ApiResponse.success("会员创建成功", member));
        } catch (Exception e) {
            log.error("创建会员失败: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        }
    }
    
    @GetMapping("/{memberId}")
    public ResponseEntity<ApiResponse<Member>> getMember(
            @PathVariable String memberId) {
        try {
            Member member = memberService.getMemberByMemberId(memberId)
                    .orElseThrow(() -> new RuntimeException("会员不存在: " + memberId));
            
            return ResponseEntity.ok(ApiResponse.success(member));
        } catch (Exception e) {
            log.error("查询会员失败: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error(404, e.getMessage()));
        }
    }
    
    @PostMapping("/{memberId}/activate")
    public ResponseEntity<ApiResponse<Member>> activateMember(
            @PathVariable String memberId) {
        try {
            Member member = memberService.activateMember(memberId);
            return ResponseEntity.ok(ApiResponse.success("会员已激活", member));
        } catch (Exception e) {
            log.error("激活会员失败: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        }
    }
    
    @PostMapping("/{memberId}/deactivate")
    public ResponseEntity<ApiResponse<Member>> deactivateMember(
            @PathVariable String memberId) {
        try {
            Member member = memberService.deactivateMember(memberId);
            return ResponseEntity.ok(ApiResponse.success("会员已停用", member));
        } catch (Exception e) {
            log.error("停用会员失败: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        }
    }
}
