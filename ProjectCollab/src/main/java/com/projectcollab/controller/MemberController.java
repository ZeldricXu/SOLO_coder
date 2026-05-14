package com.projectcollab.controller;

import com.projectcollab.dto.AddMemberRequest;
import com.projectcollab.dto.ApiResponse;
import com.projectcollab.entity.ProjectMember;
import com.projectcollab.service.member.MemberService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/members")
public class MemberController {

    @Autowired
    private MemberService memberService;

    @PostMapping
    public ApiResponse<ProjectMember> addMember(@RequestBody AddMemberRequest request) {
        ProjectMember member = memberService.addMember(request);
        return ApiResponse.success(member);
    }

    @GetMapping("/project/{projectId}")
    public ApiResponse<List<ProjectMember>> getMembersByProject(@PathVariable String projectId) {
        List<ProjectMember> members = memberService.getMembersByProjectId(projectId);
        return ApiResponse.success(members);
    }

    @GetMapping("/project/{projectId}/available")
    public ApiResponse<List<ProjectMember>> getAvailableMembers(@PathVariable String projectId) {
        List<ProjectMember> members = memberService.getAvailableMembers(projectId);
        return ApiResponse.success(members);
    }
}
