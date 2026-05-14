package com.restaurant.mgmt.controller;

import com.restaurant.mgmt.dto.ApiResponse;
import com.restaurant.mgmt.dto.ReserveTableRequest;
import com.restaurant.mgmt.dto.ReserveTableResponse;
import com.restaurant.mgmt.model.RestaurantTable;
import com.restaurant.mgmt.service.TableService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/tables")
public class TableController {

    @Autowired
    private TableService tableService;

    @PostMapping
    public ApiResponse<RestaurantTable> createTable(@RequestBody RestaurantTable table) {
        RestaurantTable saved = tableService.createTable(table);
        return ApiResponse.success(saved);
    }

    @GetMapping
    public ApiResponse<List<RestaurantTable>> getAllTables() {
        List<RestaurantTable> tables = tableService.getAllTables();
        return ApiResponse.success(tables);
    }

    @GetMapping("/{tableId}")
    public ApiResponse<RestaurantTable> getTable(@PathVariable String tableId) {
        RestaurantTable table = tableService.getTableById(tableId);
        return ApiResponse.success(table);
    }

    @GetMapping("/number/{tableNumber}")
    public ApiResponse<RestaurantTable> getTableByNumber(@PathVariable String tableNumber) {
        RestaurantTable table = tableService.getTableByNumber(tableNumber);
        return ApiResponse.success(table);
    }

    @GetMapping("/available")
    public ApiResponse<List<RestaurantTable>> getAvailableTables() {
        List<RestaurantTable> tables = tableService.getAvailableTables();
        return ApiResponse.success(tables);
    }

    @GetMapping("/available/capacity/{capacity}")
    public ApiResponse<List<RestaurantTable>> getAvailableTablesByCapacity(@PathVariable int capacity) {
        List<RestaurantTable> tables = tableService.getAvailableTablesByCapacity(capacity);
        return ApiResponse.success(tables);
    }

    @PostMapping("/reserve")
    public ApiResponse<ReserveTableResponse> reserveTable(@RequestBody ReserveTableRequest request) {
        RestaurantTable table = tableService.reserveTable(request);
        ReserveTableResponse response = new ReserveTableResponse(
            table.getTableId(),
            table.getTableNumber(),
            table.getTableStatus()
        );
        return ApiResponse.success(response);
    }

    @PostMapping("/{tableId}/cancel-reservation")
    public ApiResponse<RestaurantTable> cancelReservation(@PathVariable String tableId) {
        RestaurantTable table = tableService.cancelReservation(tableId);
        return ApiResponse.success(table);
    }

    @PostMapping("/{tableId}/occupy")
    public ApiResponse<RestaurantTable> occupyTable(@PathVariable String tableId) {
        RestaurantTable table = tableService.occupyTable(tableId);
        return ApiResponse.success(table);
    }

    @PostMapping("/{tableId}/release")
    public ApiResponse<RestaurantTable> releaseTable(@PathVariable String tableId) {
        RestaurantTable table = tableService.releaseTable(tableId);
        return ApiResponse.success(table);
    }

    @PutMapping("/{tableId}")
    public ApiResponse<RestaurantTable> updateTable(
            @PathVariable String tableId,
            @RequestBody RestaurantTable table) {
        RestaurantTable updated = tableService.updateTable(tableId, table);
        return ApiResponse.success(updated);
    }

    @DeleteMapping("/{tableId}")
    public ApiResponse<Void> deleteTable(@PathVariable String tableId) {
        tableService.deleteTable(tableId);
        return ApiResponse.success(null);
    }
}
