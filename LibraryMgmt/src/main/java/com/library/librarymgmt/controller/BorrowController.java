package com.library.librarymgmt.controller;

import com.library.librarymgmt.dto.ApiResponse;
import com.library.librarymgmt.dto.BorrowRequest;
import com.library.librarymgmt.dto.BorrowResult;
import com.library.librarymgmt.entity.Borrow;
import com.library.librarymgmt.service.BorrowService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/borrows")
public class BorrowController {

    private final BorrowService borrowService;

    public BorrowController(BorrowService borrowService) {
        this.borrowService = borrowService;
    }

    @PostMapping("/create")
    public ApiResponse<BorrowResult> createBorrow(@Validated @RequestBody BorrowRequest request) {
        return ApiResponse.success(borrowService.createBorrow(request));
    }

    @GetMapping("/{borrowId}")
    public ApiResponse<Borrow> getBorrowById(@PathVariable String borrowId) {
        Optional<Borrow> borrow = borrowService.getBorrowById(borrowId);
        if (borrow.isPresent()) {
            return ApiResponse.success(borrow.get());
        }
        return ApiResponse.error(404, "借阅记录不存在");
    }

    @GetMapping
    public ApiResponse<List<Borrow>> getAllBorrows() {
        return ApiResponse.success(borrowService.getAllBorrows());
    }

    @GetMapping("/reader/{readerId}")
    public ApiResponse<List<Borrow>> getBorrowsByReaderId(@PathVariable String readerId) {
        return ApiResponse.success(borrowService.getBorrowsByReaderId(readerId));
    }

    @GetMapping("/book/{bookId}")
    public ApiResponse<List<Borrow>> getBorrowsByBookId(@PathVariable String bookId) {
        return ApiResponse.success(borrowService.getBorrowsByBookId(bookId));
    }

    @GetMapping("/reader/{readerId}/active")
    public ApiResponse<List<Borrow>> getActiveBorrowsByReaderId(@PathVariable String readerId) {
        return ApiResponse.success(borrowService.getActiveBorrowsByReaderId(readerId));
    }

    @GetMapping("/overdue")
    public ApiResponse<List<Borrow>> getOverdueBorrows() {
        return ApiResponse.success(borrowService.getOverdueBorrows());
    }
}
