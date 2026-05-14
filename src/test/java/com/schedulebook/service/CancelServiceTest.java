package com.schedulebook.service;

import com.schedulebook.dto.CancelBookingRequest;
import com.schedulebook.exception.BookingException;
import com.schedulebook.model.*;
import com.schedulebook.repository.*;
import com.schedulebook.testdata.TestDataBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("取消模块测试 - 资源释放和状态恢复")
class CancelServiceTest {

    @Mock
    private CancelRecordRepository cancelRecordRepository;

    @Mock
    private IdGeneratorService idGeneratorService;

    @InjectMocks
    private CancelService cancelService;

    private Booking testBooking;
    private CancelBookingRequest testRequest;

    @BeforeEach
    void setUp() {
        testBooking = TestDataBuilder.buildConfirmedBooking();
        testRequest = new CancelBookingRequest();
        testRequest.setBookingId("booking_001");
        testRequest.setCancelReason("时间冲突");
        testRequest.setCancelBy("user_10086");
    }

    @Test
    @DisplayName("测试取消记录创建 - 取消原因正确记录")
    void testProcessCancel_RecordCreation() {
        when(idGeneratorService.generateCancelId()).thenReturn("cancel_001");
        when(cancelRecordRepository.save(any(CancelRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CancelRecord result = cancelService.processCancel(testBooking, testRequest);

        assertNotNull(result, "取消记录应该被创建");
        assertEquals("cancel_001", result.getCancelId());
        assertEquals("booking_001", result.getBookingId());
        assertEquals("时间冲突", result.getCancelReason());
        assertEquals("user_10086", result.getCancelBy());
        assertNotNull(result.getCancelTime());
    }

    @Test
    @DisplayName("测试取消记录创建 - 取消操作人默认为预约用户")
    void testProcessCancel_DefaultCancelBy() {
        CancelBookingRequest request = new CancelBookingRequest();
        request.setBookingId("booking_001");
        request.setCancelReason("个人原因");

        when(idGeneratorService.generateCancelId()).thenReturn("cancel_002");
        when(cancelRecordRepository.save(any(CancelRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CancelRecord result = cancelService.processCancel(testBooking, request);

        assertEquals("user_10086", result.getCancelBy(), 
                "未指定取消操作人时应该默认为预约用户");
    }
}
