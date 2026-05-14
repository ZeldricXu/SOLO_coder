package com.library.librarymgmt.controller;

import com.library.librarymgmt.dto.ApiResponse;
import com.library.librarymgmt.dto.ReturnRequest;
import com.library.librarymgmt.dto.ReturnResult;
import com.library.librarymgmt.entity.ReturnRecord;
import com.library.librarymgmt.service.ReturnService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/returns")
public class ReturnController {

    private final ReturnService returnService;

    public ReturnController(ReturnService returnService) {
        this.returnService = returnService;
    }

    @PostMapping("/process")
    public ApiResponse<ReturnResult> processReturn(@Validated @RequestBody ReturnRequest request) {
        return ApiResponse.success(returnService.processReturn(request));
    }

    @GetMapping("/{returnId}")
    public ApiResponse<ReturnRecord> getReturnById(@PathVariable String returnId) {
        Optional<ReturnRecord> returnRecord = returnService.getReturnById(returnId);
        if (returnRecord.isPresent()) {
            return ApiResponse.success(returnRecord.get());
        }
        return ApiResponse.error(404, "归还记录不存在");
    }

    @GetMapping("/borrow/{borrowId}")
    public ApiResponse<ReturnRecord> getReturnByBorrowId(@PathVariable String borrowId) {
        Optional<ReturnRecord> returnRecord = returnService.getReturnByBorrowId(borrowId);
        if (returnRecord.isPresent()) {
            return ApiResponse.success(returnRecord.get());
        }
        return ApiResponse.error(404, "归还记录不存在");
    }

    @GetMapping
    public ApiResponse<List<ReturnRecord>> getAllReturns() {
        return ApiResponse.success(returnService.getAllReturns());
    }

    @GetMapping("/reader/{readerId}")
    public ApiResponse<List<ReturnRecord>> getReturnsByReaderId(@PathVariable String readerId) {
        return ApiResponse.success(returnService.getReturnsByReaderId(readerId));
    }

    @GetMapping("/book/{bookId}")
    public ApiResponse<List<ReturnRecord>> getReturnsByBookId(@PathVariable String bookId) {
        return ApiResponse.success(returnService.getReturnsByBookId(bookId));
    }

    @GetMapping("/status/{status}")
    public ApiResponse<List<ReturnRecord>> getReturnsByStatus(@PathVariable String status) {
        return ApiResponse.success(returnService.getReturnsByStatus(status));
    }
}
