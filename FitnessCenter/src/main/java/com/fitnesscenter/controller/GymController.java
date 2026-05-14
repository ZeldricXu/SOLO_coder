package com.fitnesscenter.controller;

import com.fitnesscenter.dto.ApiResponse;
import com.fitnesscenter.model.Gym;
import com.fitnesscenter.service.GymService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/gyms")
public class GymController {

    private final GymService gymService;

    public GymController(GymService gymService) {
        this.gymService = gymService;
    }

    @PostMapping("/create")
    public ApiResponse<Gym> createGym(@RequestBody Gym gym) {
        Gym savedGym = gymService.createGym(gym);
        return ApiResponse.success(savedGym);
    }

    @GetMapping("/{gymId}")
    public ApiResponse<Gym> getGymById(@PathVariable String gymId) {
        Gym gym = gymService.getGymById(gymId);
        return ApiResponse.success(gym);
    }

    @GetMapping
    public ApiResponse<List<Gym>> getAllGyms() {
        List<Gym> gyms = gymService.getAllGyms();
        return ApiResponse.success(gyms);
    }

    @GetMapping("/active")
    public ApiResponse<List<Gym>> getActiveGyms() {
        List<Gym> gyms = gymService.getActiveGyms();
        return ApiResponse.success(gyms);
    }

    @PutMapping("/{gymId}")
    public ApiResponse<Gym> updateGym(@PathVariable String gymId, @RequestBody Gym gymDetails) {
        Gym gym = gymService.updateGym(gymId, gymDetails);
        return ApiResponse.success(gym);
    }

    @PutMapping("/{gymId}/status")
    public ApiResponse<Gym> updateGymStatus(@PathVariable String gymId, @RequestParam String status) {
        Gym gym = gymService.updateGymStatus(gymId, status);
        return ApiResponse.success(gym);
    }
}
