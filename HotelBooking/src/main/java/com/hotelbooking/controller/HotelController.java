package com.hotelbooking.controller;

import com.hotelbooking.dto.ApiResponse;
import com.hotelbooking.model.Hotel;
import com.hotelbooking.service.HotelService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/hotels")
public class HotelController {

    private final HotelService hotelService;

    public HotelController(HotelService hotelService) {
        this.hotelService = hotelService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Hotel>> createHotel(@RequestBody Hotel hotel) {
        try {
            Hotel created = hotelService.createHotel(hotel);
            return ResponseEntity.ok(ApiResponse.success("酒店创建成功", created));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Hotel>>> getAllHotels() {
        List<Hotel> hotels = hotelService.getAllHotels();
        return ResponseEntity.ok(ApiResponse.success(hotels));
    }

    @GetMapping("/active")
    public ResponseEntity<ApiResponse<List<Hotel>>> getActiveHotels() {
        List<Hotel> hotels = hotelService.getActiveHotels();
        return ResponseEntity.ok(ApiResponse.success(hotels));
    }

    @GetMapping("/{hotelId}")
    public ResponseEntity<ApiResponse<Hotel>> getHotel(@PathVariable String hotelId) {
        return hotelService.getHotelById(hotelId)
                .map(hotel -> ResponseEntity.ok(ApiResponse.success(hotel)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{hotelId}")
    public ResponseEntity<ApiResponse<Hotel>> updateHotel(@PathVariable String hotelId, @RequestBody Hotel hotelDetails) {
        try {
            Hotel updated = hotelService.updateHotel(hotelId, hotelDetails);
            return ResponseEntity.ok(ApiResponse.success("酒店更新成功", updated));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage()));
        }
    }

    @DeleteMapping("/{hotelId}")
    public ResponseEntity<ApiResponse<Void>> deleteHotel(@PathVariable String hotelId) {
        try {
            hotelService.deleteHotel(hotelId);
            return ResponseEntity.ok(ApiResponse.success("酒店已停用", null));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage()));
        }
    }
}
