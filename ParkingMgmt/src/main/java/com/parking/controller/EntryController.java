package com.parking.controller;

import com.parking.dto.ApiResponse;
import com.parking.dto.EntryRequest;
import com.parking.dto.EntryResponse;
import com.parking.entity.EntryRecord;
import com.parking.service.EntryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/entries")
public class EntryController {

    @Autowired
    private EntryService entryService;

    @PostMapping("/create")
    public ApiResponse<EntryResponse> createEntry(@RequestBody EntryRequest request) {
        EntryResponse response = entryService.processEntry(request);
        return ApiResponse.success(response);
    }

    @GetMapping("/{entryId}")
    public ApiResponse<EntryRecord> getEntry(@PathVariable String entryId) {
        EntryRecord entry = entryService.getEntryById(entryId);
        return ApiResponse.success(entry);
    }

    @GetMapping("/list")
    public ApiResponse<List<EntryRecord>> listEntries() {
        List<EntryRecord> entries = entryService.getAllEntries();
        return ApiResponse.success(entries);
    }

    @GetMapping("/active")
    public ApiResponse<List<EntryRecord>> listActiveEntries() {
        List<EntryRecord> activeEntries = entryService.getActiveEntries();
        return ApiResponse.success(activeEntries);
    }
}
