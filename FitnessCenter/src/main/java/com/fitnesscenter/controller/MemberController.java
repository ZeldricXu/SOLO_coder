package com.fitnesscenter.controller;

import com.fitnesscenter.dto.ApiResponse;
import com.fitnesscenter.dto.MemberRequest;
import com.fitnesscenter.model.Member;
import com.fitnesscenter.service.MemberService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/members")
public class MemberController {

    private final MemberService memberService;

    public MemberController(MemberService memberService) {
        this.memberService = memberService;
    }

    @PostMapping("/register")
    public ApiResponse<Member> registerMember(@RequestBody MemberRequest request) {
        Member member = memberService.registerMember(request);
        return ApiResponse.success(member);
    }

    @GetMapping("/{memberId}")
    public ApiResponse<Member> getMemberById(@PathVariable String memberId) {
        Member member = memberService.getMemberById(memberId);
        return ApiResponse.success(member);
    }

    @GetMapping
    public ApiResponse<List<Member>> getAllMembers() {
        List<Member> members = memberService.getAllMembers();
        return ApiResponse.success(members);
    }

    @PutMapping("/{memberId}")
    public ApiResponse<Member> updateMember(@PathVariable String memberId, @RequestBody MemberRequest request) {
        Member member = memberService.updateMember(memberId, request);
        return ApiResponse.success(member);
    }

    @PutMapping("/{memberId}/status")
    public ApiResponse<Void> updateMemberStatus(@PathVariable String memberId, @RequestParam String status) {
        memberService.updateMemberStatus(memberId, status);
        return ApiResponse.success(null);
    }
}
