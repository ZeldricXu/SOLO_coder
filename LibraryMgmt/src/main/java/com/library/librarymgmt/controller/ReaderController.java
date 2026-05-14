package com.library.librarymgmt.controller;

import com.library.librarymgmt.dto.ApiResponse;
import com.library.librarymgmt.dto.ReaderRequest;
import com.library.librarymgmt.entity.Reader;
import com.library.librarymgmt.service.ReaderService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/readers")
public class ReaderController {

    private final ReaderService readerService;

    public ReaderController(ReaderService readerService) {
        this.readerService = readerService;
    }

    @PostMapping
    public ApiResponse<Reader> createReader(@Validated @RequestBody ReaderRequest request) {
        return ApiResponse.success(readerService.createReader(request));
    }

    @GetMapping("/{readerId}")
    public ApiResponse<Reader> getReaderById(@PathVariable String readerId) {
        Optional<Reader> reader = readerService.getReaderById(readerId);
        if (reader.isPresent()) {
            return ApiResponse.success(reader.get());
        }
        return ApiResponse.error(404, "读者不存在");
    }

    @GetMapping
    public ApiResponse<List<Reader>> getAllReaders() {
        return ApiResponse.success(readerService.getAllReaders());
    }

    @GetMapping("/status/{status}")
    public ApiResponse<List<Reader>> getReadersByStatus(@PathVariable String status) {
        return ApiResponse.success(readerService.getReadersByStatus(status));
    }

    @GetMapping("/type/{type}")
    public ApiResponse<List<Reader>> getReadersByType(@PathVariable String type) {
        return ApiResponse.success(readerService.getReadersByType(type));
    }

    @PutMapping("/{readerId}")
    public ApiResponse<Reader> updateReader(@PathVariable String readerId, @Validated @RequestBody ReaderRequest request) {
        return ApiResponse.success(readerService.updateReader(readerId, request));
    }

    @DeleteMapping("/{readerId}")
    public ApiResponse<Void> deleteReader(@PathVariable String readerId) {
        readerService.deleteReader(readerId);
        return ApiResponse.success(200, "删除成功", null);
    }

    @GetMapping("/{readerId}/can-borrow")
    public ApiResponse<Boolean> canBorrow(@PathVariable String readerId) {
        return ApiResponse.success(readerService.canBorrow(readerId));
    }
}
