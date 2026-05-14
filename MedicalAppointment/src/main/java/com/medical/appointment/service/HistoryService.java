package com.medical.appointment.service;

import com.medical.appointment.entity.AppointmentHistory;
import com.medical.appointment.repository.AppointmentHistoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Transactional
public class HistoryService {
    
    private final AppointmentHistoryRepository historyRepository;
    
    public HistoryService(AppointmentHistoryRepository historyRepository) {
        this.historyRepository = historyRepository;
    }
    
    public void recordHistory(String appointmentId, String actionType, 
                             String previousStatus, String newStatus, 
                             String actionBy, String remark) {
        AppointmentHistory history = new AppointmentHistory();
        history.setAppointmentId(appointmentId);
        history.setActionType(actionType);
        history.setPreviousStatus(previousStatus);
        history.setNewStatus(newStatus);
        history.setActionTime(LocalDateTime.now());
        history.setActionBy(actionBy);
        history.setRemark(remark);
        historyRepository.save(history);
    }
    
    public List<AppointmentHistory> getHistoryByAppointment(String appointmentId) {
        return historyRepository.findByAppointmentIdOrderByActionTimeDesc(appointmentId);
    }
    
    public List<AppointmentHistory> getHistoryByActionType(String actionType) {
        return historyRepository.findByActionType(actionType);
    }
    
    public List<AppointmentHistory> getAllHistory() {
        return historyRepository.findAll();
    }
}
