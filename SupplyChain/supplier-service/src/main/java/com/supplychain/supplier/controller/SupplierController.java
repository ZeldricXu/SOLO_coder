package com.supplychain.supplier.controller;

import com.supplychain.common.dto.ResponseResult;
import com.supplychain.common.entity.Supplier;
import com.supplychain.supplier.service.SupplierService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "供应商管理", description = "供应商信息管理接口")
@RestController
@RequestMapping("/api/suppliers")
@RequiredArgsConstructor
public class SupplierController {

    private final SupplierService supplierService;

    @Operation(summary = "创建供应商")
    @PostMapping
    public ResponseResult<Supplier> createSupplier(@RequestBody Supplier supplier) {
        return ResponseResult.success(supplierService.createSupplier(supplier));
    }

    @Operation(summary = "获取供应商详情")
    @GetMapping("/{supplierId}")
    public ResponseResult<Supplier> getSupplier(@PathVariable String supplierId) {
        return ResponseResult.success(supplierService.getSupplier(supplierId));
    }

    @Operation(summary = "获取供应商列表")
    @GetMapping
    public ResponseResult<List<Supplier>> listSuppliers(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type) {
        return ResponseResult.success(supplierService.listSuppliers(status, type));
    }

    @Operation(summary = "更新供应商信息")
    @PutMapping("/{supplierId}")
    public ResponseResult<Supplier> updateSupplier(
            @PathVariable String supplierId,
            @RequestBody Supplier supplier) {
        return ResponseResult.success(supplierService.updateSupplier(supplierId, supplier));
    }

    @Operation(summary = "停用供应商")
    @DeleteMapping("/{supplierId}")
    public ResponseResult<Void> deleteSupplier(@PathVariable String supplierId) {
        supplierService.deleteSupplier(supplierId);
        return ResponseResult.success();
    }

    @Operation(summary = "检查供应商资质")
    @GetMapping("/{supplierId}/validate")
    public ResponseResult<Boolean> validateSupplier(@PathVariable String supplierId) {
        supplierService.validateSupplier(supplierId);
        return ResponseResult.success(true);
    }

    @Operation(summary = "获取合格供应商列表")
    @GetMapping("/qualified")
    public ResponseResult<List<Supplier>> findQualifiedSuppliers(
            @RequestParam(required = false) String type) {
        return ResponseResult.success(supplierService.findQualifiedSuppliers(type));
    }
}
