package com.contractmgmt.controller;

import com.contractmgmt.dto.ApiResponse;
import com.contractmgmt.entity.ArchiveRecord;
import com.contractmgmt.service.ArchiveService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/archives")
public class ArchiveController {

    private final ArchiveService archiveService;

    public ArchiveController(ArchiveService archiveService) {
        this.archiveService = archiveService;
    }

    @PostMapping("/archive")
    public ApiResponse<ArchiveRecord> archiveContract(
            @RequestParam String contractId,
            @RequestParam(required = false) String operator,
            @RequestParam(required = false) String reason) {
        ArchiveRecord archive = archiveService.archiveContract(contractId, operator, reason);
        return ApiResponse.success(archive);
    }

    @GetMapping("/{archiveId}")
    public ApiResponse<ArchiveRecord> getArchive(@PathVariable String archiveId) {
        ArchiveRecord archive = archiveService.getArchive(archiveId);
        return ApiResponse.success(archive);
    }

    @GetMapping("/contract/{contractId}")
    public ApiResponse<Optional<ArchiveRecord>> getArchiveByContract(
            @PathVariable String contractId) {
        Optional<ArchiveRecord> archive = archiveService.getArchiveByContract(contractId);
        return ApiResponse.success(archive);
    }

    @GetMapping("/search")
    public ApiResponse<List<ArchiveRecord>> searchArchives(
            @RequestParam(required = false) String keyword) {
        List<ArchiveRecord> archives = archiveService.searchArchives(keyword);
        return ApiResponse.success(archives);
    }
}
