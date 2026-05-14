package com.library.librarymgmt.controller;

import com.library.librarymgmt.dto.ApiResponse;
import com.library.librarymgmt.dto.ReserveRequest;
import com.library.librarymgmt.dto.ReserveResult;
import com.library.librarymgmt.entity.Reserve;
import com.library.librarymgmt.service.ReserveService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/reserves")
public class ReserveController {

    private final ReserveService reserveService;

    public ReserveController(ReserveService reserveService) {
        this.reserveService = reserveService;
    }

    @PostMapping("/create")
    public ApiResponse<ReserveResult> createReserve(@Validated @RequestBody ReserveRequest request) {
        return ApiResponse.success(reserveService.createReserve(request));
    }

    @GetMapping("/{reserveId}")
    public ApiResponse<Reserve> getReserveById(@PathVariable String reserveId) {
        Optional<Reserve> reserve = reserveService.getReserveById(reserveId);
        if (reserve.isPresent()) {
            return ApiResponse.success(reserve.get());
        }
        return ApiResponse.error(404, "预约记录不存在");
    }

    @GetMapping
    public ApiResponse<List<Reserve>> getAllReserves() {
        return ApiResponse.success(reserveService.getAllReserves());
    }

    @GetMapping("/book/{bookId}")
    public ApiResponse<List<Reserve>> getReservesByBookId(@PathVariable String bookId) {
        return ApiResponse.success(reserveService.getReservesByBookId(bookId));
    }

    @GetMapping("/reader/{readerId}")
    public ApiResponse<List<Reserve>> getReservesByReaderId(@PathVariable String readerId) {
        return ApiResponse.success(reserveService.getReservesByReaderId(readerId));
    }

    @GetMapping("/book/{bookId}/waiting")
    public ApiResponse<List<Reserve>> getWaitingReservesByBookId(@PathVariable String bookId) {
        return ApiResponse.success(reserveService.getWaitingReservesByBookId(bookId));
    }
}
