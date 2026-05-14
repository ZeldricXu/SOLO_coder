package com.schedulebook.service;

import com.schedulebook.dto.CancelBookingRequest;
import com.schedulebook.model.Booking;
import com.schedulebook.model.CancelRecord;
import com.schedulebook.repository.CancelRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class CancelService {
    
    private static final Logger logger = LoggerFactory.getLogger(CancelService.class);
    
    @Autowired
    private CancelRecordRepository cancelRecordRepository;
    
    @Autowired
    private IdGeneratorService idGeneratorService;
    
    @Transactional
    public CancelRecord processCancel(Booking booking, CancelBookingRequest request) {
        logger.info("处理预约取消，预约ID: {}", booking.getBookingId());
        
        CancelRecord cancelRecord = new CancelRecord();
        cancelRecord.setCancelId(idGeneratorService.generateCancelId());
        cancelRecord.setBookingId(booking.getBookingId());
        cancelRecord.setCancelReason(request.getCancelReason());
        cancelRecord.setCancelTime(LocalDateTime.now());
        cancelRecord.setCancelBy(request.getCancelBy() != null ? request.getCancelBy() : booking.getUserId());
        
        cancelRecord = cancelRecordRepository.save(cancelRecord);
        logger.info("取消记录创建成功，取消ID: {}", cancelRecord.getCancelId());
        return cancelRecord;
    }
    
    public CancelRecord getCancelRecord(String cancelId) {
        return cancelRecordRepository.findByCancelId(cancelId)
                .orElseThrow(() -> new RuntimeException("取消记录不存在"));
    }
}
