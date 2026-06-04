package com.cicd.server.approval;

import com.cicd.server.entity.Approval;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/approvals")
@RequiredArgsConstructor
public class ApprovalController {

    private final ApprovalService approvalService;

    @GetMapping("/{id}")
    public ResponseEntity<Approval> getApproval(@PathVariable Long id) {
        Approval approval = approvalService.getApproval(id);
        return approval != null ? ResponseEntity.ok(approval) : ResponseEntity.notFound().build();
    }

    @GetMapping("/pending")
    public ResponseEntity<List<Approval>> getPendingApprovals(@AuthenticationPrincipal UserDetails userDetails) {
        String approver = userDetails != null ? userDetails.getUsername() : "";
        return ResponseEntity.ok(approvalService.getPendingApprovals(approver));
    }

    @GetMapping("/project/{projectId}")
    public ResponseEntity<List<Approval>> getApprovalHistory(
            @PathVariable Long projectId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(approvalService.getApprovalHistory(projectId, page, size));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<Approval> approve(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body,
            @AuthenticationPrincipal UserDetails userDetails) {
        String approver = userDetails != null ? userDetails.getUsername() : "";
        String comment = body != null ? body.get("comment") : null;
        Approval approval = approvalService.approve(id, approver, comment);
        return ResponseEntity.ok(approval);
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<Approval> reject(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body,
            @AuthenticationPrincipal UserDetails userDetails) {
        String approver = userDetails != null ? userDetails.getUsername() : "";
        String comment = body != null ? body.get("comment") : null;
        Approval approval = approvalService.reject(id, approver, comment);
        return ResponseEntity.ok(approval);
    }
}
