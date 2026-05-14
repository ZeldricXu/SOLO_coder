package com.restaurant.mgmt.service;

import com.restaurant.mgmt.dto.ReserveTableRequest;
import com.restaurant.mgmt.exception.BusinessException;
import com.restaurant.mgmt.model.RestaurantTable;
import com.restaurant.mgmt.repository.RestaurantTableRepository;
import com.restaurant.mgmt.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class TableService {

    @Autowired
    private RestaurantTableRepository tableRepository;

    @Autowired
    private HistoryService historyService;

    public RestaurantTable createTable(RestaurantTable table) {
        if (table.getTableNumber() == null || table.getTableNumber().trim().isEmpty()) {
            throw new BusinessException("桌号不能为空");
        }
        if (tableRepository.findByTableNumber(table.getTableNumber()).isPresent()) {
            throw new BusinessException("桌号已存在");
        }
        
        table.setTableId(IdGenerator.generateTableId());
        table.setCreatedAt(LocalDateTime.now());
        table.setUpdatedAt(LocalDateTime.now());
        if (table.getTableStatus() == null) {
            table.setTableStatus("available");
        }
        
        RestaurantTable saved = tableRepository.save(table);
        historyService.recordHistory("table", saved.getTableId(), "创建桌位", 
            "创建桌位: " + saved.getTableNumber(), "system", "create", "success");
        return saved;
    }

    public RestaurantTable updateTable(String tableId, RestaurantTable table) {
        Optional<RestaurantTable> existingOpt = tableRepository.findById(tableId);
        if (existingOpt.isEmpty()) {
            throw new BusinessException("桌位不存在");
        }
        
        RestaurantTable existing = existingOpt.get();
        if (table.getTableNumber() != null) {
            Optional<RestaurantTable> sameNumber = tableRepository.findByTableNumber(table.getTableNumber());
            if (sameNumber.isPresent() && !sameNumber.get().getTableId().equals(tableId)) {
                throw new BusinessException("桌号已存在");
            }
            existing.setTableNumber(table.getTableNumber());
        }
        if (table.getTableType() != null) {
            existing.setTableType(table.getTableType());
        }
        if (table.getTableCapacity() > 0) {
            existing.setTableCapacity(table.getTableCapacity());
        }
        if (table.getTableStatus() != null) {
            existing.setTableStatus(table.getTableStatus());
        }
        existing.setUpdatedAt(LocalDateTime.now());
        
        return tableRepository.save(existing);
    }

    public void deleteTable(String tableId) {
        if (!tableRepository.existsById(tableId)) {
            throw new BusinessException("桌位不存在");
        }
        RestaurantTable table = getTableById(tableId);
        if (!"available".equals(table.getTableStatus())) {
            throw new BusinessException("桌位当前状态不允许删除");
        }
        tableRepository.deleteById(tableId);
    }

    public RestaurantTable getTableById(String tableId) {
        return tableRepository.findById(tableId)
                .orElseThrow(() -> new BusinessException("桌位不存在"));
    }

    public RestaurantTable getTableByNumber(String tableNumber) {
        return tableRepository.findByTableNumber(tableNumber)
                .orElseThrow(() -> new BusinessException("桌位不存在"));
    }

    public List<RestaurantTable> getAllTables() {
        return tableRepository.findAll();
    }

    public List<RestaurantTable> getTablesByStatus(String status) {
        return tableRepository.findByTableStatus(status);
    }

    public List<RestaurantTable> getAvailableTables() {
        return tableRepository.findByTableStatus("available");
    }

    public List<RestaurantTable> getAvailableTablesByCapacity(int capacity) {
        return tableRepository.findByTableStatusAndTableCapacityGreaterThanEqual("available", capacity);
    }

    @Transactional
    public RestaurantTable reserveTable(ReserveTableRequest request) {
        RestaurantTable table;
        if (request.getTableId() != null) {
            table = getTableById(request.getTableId());
        } else if (request.getTableNumber() != null) {
            table = getTableByNumber(request.getTableNumber());
        } else {
            throw new BusinessException("请指定桌位ID或桌号");
        }

        if ("occupied".equals(table.getTableStatus())) {
            throw new BusinessException("桌位已被占用");
        }
        if ("reserved".equals(table.getTableStatus())) {
            throw new BusinessException("桌位已被预约");
        }

        table.setTableStatus("reserved");
        table.setReserveTime(request.getReserveTime() != null ? request.getReserveTime() : LocalDateTime.now());
        table.setReserveCustomerName(request.getCustomerName());
        table.setReserveCustomerPhone(request.getCustomerPhone());
        table.setReserveRemark(request.getRemark());
        table.setUpdatedAt(LocalDateTime.now());
        
        RestaurantTable saved = tableRepository.save(table);
        historyService.recordHistory("table", saved.getTableId(), "预约桌位", 
            "桌位预约: " + saved.getTableNumber() + ", 客户: " + request.getCustomerName(), 
            "system", "reserve", "success");
        
        return saved;
    }

    @Transactional
    public RestaurantTable cancelReservation(String tableId) {
        RestaurantTable table = getTableById(tableId);
        if (!"reserved".equals(table.getTableStatus())) {
            throw new BusinessException("桌位未被预约");
        }
        
        table.setTableStatus("available");
        table.setReserveTime(null);
        table.setReserveCustomerName(null);
        table.setReserveCustomerPhone(null);
        table.setReserveRemark(null);
        table.setUpdatedAt(LocalDateTime.now());
        
        RestaurantTable saved = tableRepository.save(table);
        historyService.recordHistory("table", saved.getTableId(), "取消预约", 
            "取消桌位预约: " + saved.getTableNumber(), "system", "cancel_reserve", "success");
        
        return saved;
    }

    @Transactional
    public RestaurantTable occupyTable(String tableId) {
        RestaurantTable table = getTableById(tableId);
        if ("occupied".equals(table.getTableStatus())) {
            throw new BusinessException("桌位已被占用");
        }
        
        table.setTableStatus("occupied");
        table.setUpdatedAt(LocalDateTime.now());
        
        return tableRepository.save(table);
    }

    @Transactional
    public RestaurantTable releaseTable(String tableId) {
        RestaurantTable table = getTableById(tableId);
        
        table.setTableStatus("available");
        table.setReserveTime(null);
        table.setReserveCustomerName(null);
        table.setReserveCustomerPhone(null);
        table.setReserveRemark(null);
        table.setUpdatedAt(LocalDateTime.now());
        
        RestaurantTable saved = tableRepository.save(table);
        historyService.recordHistory("table", saved.getTableId(), "释放桌位", 
            "释放桌位: " + saved.getTableNumber(), "system", "release", "success");
        
        return saved;
    }

    public boolean isTableAvailable(String tableId) {
        Optional<RestaurantTable> tableOpt = tableRepository.findById(tableId);
        return tableOpt.isPresent() && "available".equals(tableOpt.get().getTableStatus());
    }

    public boolean isTableReservedOrAvailable(String tableId) {
        Optional<RestaurantTable> tableOpt = tableRepository.findById(tableId);
        if (tableOpt.isEmpty()) {
            return false;
        }
        String status = tableOpt.get().getTableStatus();
        return "available".equals(status) || "reserved".equals(status);
    }
}
