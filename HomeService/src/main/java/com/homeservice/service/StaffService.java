package com.homeservice.service;

import com.homeservice.dto.StaffRequest;
import com.homeservice.entity.Staff;
import com.homeservice.enums.StaffStatus;
import com.homeservice.exception.BusinessException;
import com.homeservice.exception.ResourceNotFoundException;
import com.homeservice.repository.StaffRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class StaffService {

    @Autowired
    private StaffRepository staffRepository;

    private final AtomicLong staffCounter = new AtomicLong(0);

    public Staff createStaff(StaffRequest request) {
        String staffId = "staff_" + String.format("%03d", staffCounter.incrementAndGet());
        Staff staff = new Staff(
            staffId,
            request.getStaffName(),
            request.getStaffType(),
            request.getStaffPhone(),
            request.getStaffRegion(),
            request.getStaffPrice()
        );
        return staffRepository.save(staff);
    }

    public List<Staff> getAllStaff() {
        return staffRepository.findAll();
    }

    public Staff getStaffById(String staffId) {
        return staffRepository.findByStaffId(staffId)
            .orElseThrow(() -> new ResourceNotFoundException("Staff not found: " + staffId));
    }

    public Staff updateStaff(String staffId, StaffRequest request) {
        Staff staff = getStaffById(staffId);
        staff.setStaffName(request.getStaffName());
        staff.setStaffType(request.getStaffType());
        staff.setStaffPhone(request.getStaffPhone());
        staff.setStaffRegion(request.getStaffRegion());
        staff.setStaffPrice(request.getStaffPrice());
        return staffRepository.save(staff);
    }

    public void deleteStaff(String staffId) {
        Staff staff = getStaffById(staffId);
        staffRepository.delete(staff);
    }

    public List<Staff> getAvailableStaff() {
        return staffRepository.findByStaffStatus(StaffStatus.AVAILABLE);
    }

    public List<Staff> getStaffByType(String type) {
        return staffRepository.findByStaffType(type);
    }

    public List<Staff> getStaffByRegion(String region) {
        return staffRepository.findByStaffRegion(region);
    }

    public Staff updateStaffStatus(String staffId, StaffStatus status) {
        Staff staff = getStaffById(staffId);
        staff.setStaffStatus(status);
        return staffRepository.save(staff);
    }

    public void incrementBookingCount(String staffId) {
        Staff staff = getStaffById(staffId);
        staff.setTotalBookings(staff.getTotalBookings() + 1);
        staffRepository.save(staff);
    }

    public void incrementReviewCount(String staffId) {
        Staff staff = getStaffById(staffId);
        staff.setTotalReviews(staff.getTotalReviews() + 1);
        staffRepository.save(staff);
    }

    public void updateStaffRating(String staffId, Double newRating) {
        Staff staff = getStaffById(staffId);
        Double avgRating = staffRepository.getAverageRatingByStaffId(staffId);
        if (avgRating != null) {
            staff.setStaffRating(avgRating);
        }
        staffRepository.save(staff);
    }

    public void addStaffIncome(String staffId, Double income) {
        Staff staff = getStaffById(staffId);
        staff.setTotalIncome(staff.getTotalIncome() + income);
        staffRepository.save(staff);
    }

    public void validateStaffAvailability(String staffId) {
        Staff staff = getStaffById(staffId);
        if (staff.getStaffStatus() == StaffStatus.UNAVAILABLE) {
            throw new BusinessException("Staff is unavailable");
        }
    }
}
