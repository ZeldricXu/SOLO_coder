package com.hotelbooking.controller;

import com.hotelbooking.config.RoomTypeConfig.RoomTypeConfigEntry;
import com.hotelbooking.dto.ApiResponse;
import com.hotelbooking.model.Room;
import com.hotelbooking.service.RoomService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/rooms")
public class RoomController {

    private final RoomService roomService;

    public RoomController(RoomService roomService) {
        this.roomService = roomService;
    }

    @GetMapping("/search")
    public ResponseEntity<ApiResponse<Map<String, Object>>> searchRooms(
            @RequestParam String hotelId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkInDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkOutDate) {
        
        try {
            List<Room> availableRooms = roomService.searchAvailableRooms(hotelId, checkInDate, checkOutDate);
            
            List<Map<String, Object>> roomList = availableRooms.stream().map(room -> {
                Map<String, Object> roomInfo = new HashMap<>();
                roomInfo.put("room_id", room.getRoomId());
                roomInfo.put("room_type", room.getRoomType());
                roomInfo.put("price", room.getRoomPrice());
                roomInfo.put("room_number", room.getRoomNumber());
                roomInfo.put("room_features", room.getRoomFeatures());
                return roomInfo;
            }).collect(Collectors.toList());

            Map<String, Object> data = new HashMap<>();
            data.put("rooms", roomList);
            
            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage()));
        }
    }

    @GetMapping("/{roomId}")
    public ResponseEntity<ApiResponse<Room>> getRoom(@PathVariable String roomId) {
        return roomService.getRoomById(roomId)
                .map(room -> ResponseEntity.ok(ApiResponse.success(room)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/hotel/{hotelId}")
    public ResponseEntity<ApiResponse<List<Room>>> getRoomsByHotel(@PathVariable String hotelId) {
        List<Room> rooms = roomService.getRoomsByHotel(hotelId);
        return ResponseEntity.ok(ApiResponse.success(rooms));
    }

    @GetMapping("/hotel/{hotelId}/type/{roomType}")
    public ResponseEntity<ApiResponse<List<Room>>> getRoomsByType(
            @PathVariable String hotelId,
            @PathVariable String roomType) {
        try {
            List<Room> rooms = roomService.getRoomsByType(hotelId, roomType);
            return ResponseEntity.ok(ApiResponse.success(rooms));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage()));
        }
    }

    @GetMapping("/types")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getAllRoomTypes() {
        Map<String, Object> data = new HashMap<>();
        data.put("defaultType", roomService.getDefaultRoomType());
        data.put("allTypes", roomService.getAllRoomTypes());
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @GetMapping("/types/{roomType}")
    public ResponseEntity<ApiResponse<RoomTypeConfigEntry>> getRoomTypeConfig(@PathVariable String roomType) {
        RoomTypeConfigEntry config = roomService.getRoomTypeConfig(roomType);
        if (config == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(ApiResponse.success(config));
    }

    @GetMapping("/types/validate/{roomType}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> validateRoomType(@PathVariable String roomType) {
        boolean valid = roomService.isValidRoomType(roomType);
        Map<String, Object> data = new HashMap<>();
        data.put("roomType", roomType);
        data.put("valid", valid);
        if (valid) {
            RoomTypeConfigEntry config = roomService.getRoomTypeConfig(roomType);
            data.put("config", config);
        }
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @GetMapping("/price/calculate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> calculateRoomPrice(
            @RequestParam String roomType,
            @RequestParam int days) {
        try {
            Double price = roomService.calculateRoomPrice(roomType, days);
            if (price == null) {
                return ResponseEntity.badRequest().body(ApiResponse.error(400, "无效的房间类型: " + roomType));
            }
            Map<String, Object> data = new HashMap<>();
            data.put("roomType", roomType);
            data.put("days", days);
            data.put("totalPrice", price);
            data.put("dailyPrice", price / days);
            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage()));
        }
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Room>> createRoom(@RequestBody Room room, @RequestParam String hotelId) {
        try {
            Room created = roomService.createRoom(room, hotelId);
            return ResponseEntity.ok(ApiResponse.success("房间创建成功", created));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage()));
        }
    }

    @PutMapping("/{roomId}")
    public ResponseEntity<ApiResponse<Room>> updateRoom(@PathVariable String roomId, @RequestBody Room roomDetails) {
        try {
            Room updated = roomService.updateRoom(roomId, roomDetails);
            return ResponseEntity.ok(ApiResponse.success("房间更新成功", updated));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage()));
        }
    }

    @PutMapping("/{roomId}/status")
    public ResponseEntity<ApiResponse<Room>> updateRoomStatus(@PathVariable String roomId, @RequestParam String status) {
        try {
            Room updated = roomService.updateRoomStatus(roomId, status);
            return ResponseEntity.ok(ApiResponse.success("状态更新成功", updated));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage()));
        }
    }
}
