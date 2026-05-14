package com.fooddelivery.controller;

import com.fooddelivery.dto.ApiResponse;
import com.fooddelivery.entity.Rider;
import com.fooddelivery.service.RiderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/riders")
public class RiderController {

    @Autowired
    private RiderService riderService;

    @PostMapping
    public ResponseEntity<ApiResponse<Rider>> createRider(@RequestBody Rider rider) {
        Rider saved = riderService.createRider(rider);
        return ResponseEntity.ok(ApiResponse.success(saved));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Rider>>> getAllRiders() {
        List<Rider> riders = riderService.getAllRiders();
        return ResponseEntity.ok(ApiResponse.success(riders));
    }

    @GetMapping("/{riderId}")
    public ResponseEntity<ApiResponse<Rider>> getRider(@PathVariable String riderId) {
        Optional<Rider> rider = riderService.getRiderById(riderId);
        if (rider.isPresent()) {
            return ResponseEntity.ok(ApiResponse.success(rider.get()));
        }
        return ResponseEntity.ok(ApiResponse.error(404, "骑手不存在"));
    }

    @GetMapping("/region/{region}")
    public ResponseEntity<ApiResponse<List<Rider>>> getRidersByRegion(@PathVariable String region) {
        List<Rider> riders = riderService.getRidersByRegion(region);
        return ResponseEntity.ok(ApiResponse.success(riders));
    }

    @GetMapping("/region/{region}/available")
    public ResponseEntity<ApiResponse<List<Rider>>> getAvailableRiders(@PathVariable String region) {
        List<Rider> riders = riderService.getAvailableRiders(region);
        return ResponseEntity.ok(ApiResponse.success(riders));
    }

    @PutMapping("/{riderId}")
    public ResponseEntity<ApiResponse<Rider>> updateRider(@PathVariable String riderId,
                                                          @RequestBody Rider rider) {
        Rider updated = riderService.updateRider(riderId, rider);
        return ResponseEntity.ok(ApiResponse.success(updated));
    }

    @PutMapping("/{riderId}/status")
    public ResponseEntity<ApiResponse<Rider>> updateStatus(@PathVariable String riderId,
                                                           @RequestParam String status) {
        Rider updated = riderService.updateRiderStatus(riderId, status);
        return ResponseEntity.ok(ApiResponse.success(updated));
    }
}
