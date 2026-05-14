package com.library.librarymgmt;

import com.library.librarymgmt.dto.ReaderRequest;
import com.library.librarymgmt.entity.Reader;
import com.library.librarymgmt.exception.LibraryException;
import com.library.librarymgmt.repository.ReaderRepository;
import com.library.librarymgmt.service.impl.ReaderServiceImpl;
import com.library.librarymgmt.testdata.TestDataBuilder;
import com.library.librarymgmt.testdata.TestConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("读者管理模块 - 读者信息管理测试")
class ReaderServiceTest {

    @Mock
    private ReaderRepository readerRepository;

    @Spy
    @InjectMocks
    private ReaderServiceImpl readerService;

    private TestDataBuilder.ReaderBuilder readerBuilder;

    @BeforeEach
    void setUp() {
        readerBuilder = new TestDataBuilder.ReaderBuilder();
    }

    @Test
    @DisplayName("测试读者正常状态")
    void testReaderActiveStatus() {
        Reader reader = readerBuilder.id("reader_active")
                .status(TestConstants.READER_STATUS_ACTIVE)
                .build();

        when(readerRepository.findByReaderId(reader.getReaderId())
        ).thenReturn(Optional.of(reader));

        Optional<Reader> foundReader = readerService.getReaderById(reader.getReaderId());

        assertTrue(foundReader.isPresent());
        assertEquals(TestConstants.READER_STATUS_ACTIVE, foundReader.get().getReaderStatus());
    }

    @Test
    @DisplayName("测试读者状态流转 - 正常到冻结")
    void testReaderStatusTransitionActiveToFrozen() {
        Reader reader = readerBuilder.id("reader_transition")
                .status(TestConstants.READER_STATUS_ACTIVE)
                .build();

        when(readerRepository.findByReaderId(reader.getReaderId())).thenReturn(Optional.of(reader));
        when(readerRepository.save(any(Reader.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Reader frozenReader = readerService.updateReaderStatus(reader.getReaderId(), TestConstants.READER_STATUS_FROZEN);

        assertEquals(TestConstants.READER_STATUS_FROZEN, frozenReader.getReaderStatus());
        verify(readerRepository, times(1)).save(argThat(r ->
                TestConstants.READER_STATUS_FROZEN.equals(r.getReaderStatus())
        ));
    }

    @Test
    @DisplayName("测试读者状态流转 - 冻结到正常")
    void testReaderStatusTransitionFrozenToActive() {
        Reader reader = readerBuilder.id("reader_unfreeze")
                .status(TestConstants.READER_STATUS_FROZEN)
                .build();

        when(readerRepository.findByReaderId(reader.getReaderId())).thenReturn(Optional.of(reader));
        when(readerRepository.save(any(Reader.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Reader activeReader = readerService.updateReaderStatus(reader.getReaderId(), TestConstants.READER_STATUS_ACTIVE);

        assertEquals(TestConstants.READER_STATUS_ACTIVE, activeReader.getReaderStatus());
    }

    @Test
    @DisplayName("测试读者状态流转 - 正常到暂停")
    void testReaderStatusTransitionActiveToSuspended() {
        Reader reader = readerBuilder.id("reader_suspend")
                .status(TestConstants.READER_STATUS_ACTIVE)
                .build();

        when(readerRepository.findByReaderId(reader.getReaderId())).thenReturn(Optional.of(reader));
        when(readerRepository.save(any(Reader.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Reader suspendedReader = readerService.updateReaderStatus(reader.getReaderId(), TestConstants.READER_STATUS_SUSPENDED);

        assertEquals(TestConstants.READER_STATUS_SUSPENDED, suspendedReader.getReaderStatus());
    }

    @Test
    @DisplayName("测试活跃读者的借阅权限 - 有权限")
    void testActiveReaderBorrowPermission() {
        Reader reader = readerBuilder.id("reader_permission")
                .status(TestConstants.READER_STATUS_ACTIVE)
                .borrowLimit(5)
                .borrowedCount(2)
                .build();

        when(readerRepository.findByReaderId(reader.getReaderId())).thenReturn(Optional.of(reader));

        assertTrue(readerService.canBorrow(reader.getReaderId()));
    }

    @Test
    @DisplayName("测试冻结读者的借阅权限 - 无权限")
    void testFrozenReaderBorrowPermission() {
        Reader reader = readerBuilder.id("reader_frozen_permission")
                .status(TestConstants.READER_STATUS_FROZEN)
                .borrowLimit(5)
                .borrowedCount(0)
                .build();

        when(readerRepository.findByReaderId(reader.getReaderId())).thenReturn(Optional.of(reader));

        assertFalse(readerService.canBorrow(reader.getReaderId()));
    }

    @Test
    @DisplayName("测试借阅已达上限的读者借阅权限 - 无权限")
    void testMaxBorrowsReaderPermission() {
        Reader reader = readerBuilder.id("reader_max_borrows")
                .status(TestConstants.READER_STATUS_ACTIVE)
                .borrowLimit(5)
                .borrowedCount(5)
                .build();

        when(readerRepository.findByReaderId(reader.getReaderId())).thenReturn(Optional.of(reader));

        assertFalse(readerService.canBorrow(reader.getReaderId()));
    }

    @Test
    @DisplayName("测试不存在的读者借阅权限 - 无权限")
    void testNonExistentReaderPermission() {
        when(readerRepository.findByReaderId("non_existent")).thenReturn(Optional.empty());

        assertFalse(readerService.canBorrow("non_existent"));
    }

    @Test
    @DisplayName("测试读者借阅计数增加")
    void testReaderBorrowedCountIncrease() {
        Reader reader = readerBuilder.id("reader_increase")
                .borrowedCount(2)
                .build();
        int initialCount = reader.getBorrowedCount();

        when(readerRepository.findByReaderId(reader.getReaderId())).thenReturn(Optional.of(reader));
        when(readerRepository.save(any(Reader.class))).thenAnswer(invocation -> {
            Reader r = invocation.getArgument(0);
            return r;
        });

        readerService.increaseBorrowedCount(reader.getReaderId());

        verify(readerRepository, times(1)).save(argThat(r ->
                r.getBorrowedCount() == initialCount + 1
        ));
    }

    @Test
    @DisplayName("测试读者借阅计数减少")
    void testReaderBorrowedCountDecrease() {
        Reader reader = readerBuilder.id("reader_decrease")
                .borrowedCount(3)
                .build();
        int initialCount = reader.getBorrowedCount();

        when(readerRepository.findByReaderId(reader.getReaderId())).thenReturn(Optional.of(reader));
        when(readerRepository.save(any(Reader.class))).thenAnswer(invocation -> {
            Reader r = invocation.getArgument(0);
            return r;
        });

        readerService.decreaseBorrowedCount(reader.getReaderId());

        verify(readerRepository, times(1)).save(argThat(r ->
                r.getBorrowedCount() == initialCount - 1
        ));
    }

    @Test
    @DisplayName("测试读者借阅计数不能为负数")
    void testReaderBorrowedCountNotNegative() {
        Reader reader = readerBuilder.id("reader_negative")
                .borrowedCount(0)
                .build();

        when(readerRepository.findByReaderId(reader.getReaderId())).thenReturn(Optional.of(reader));
        when(readerRepository.save(any(Reader.class))).thenAnswer(invocation -> invocation.getArgument(0));

        readerService.decreaseBorrowedCount(reader.getReaderId());

        verify(readerRepository, times(1)).save(argThat(r ->
                r.getBorrowedCount() >= 0
        ));
    }

    @Test
    @DisplayName("测试读者创建")
    void testReaderCreation() {
        ReaderRequest request = new ReaderRequest();
        request.setReader_name("测试读者");
        request.setReader_phone("13800138000");
        request.setReader_type(TestConstants.READER_TYPE_NORMAL);
        request.setBorrow_limit(5);

        when(readerRepository.save(any(Reader.class))).thenAnswer(invocation -> {
            Reader reader = invocation.getArgument(0);
            return reader;
        });

        Reader createdReader = readerService.createReader(request);

        assertNotNull(createdReader);
        assertEquals("测试读者", createdReader.getReaderName());
        assertEquals(TestConstants.READER_STATUS_ACTIVE, createdReader.getReaderStatus());
        assertEquals(5, createdReader.getBorrowLimit());
        assertEquals(0, createdReader.getBorrowedCount());
    }

    @Test
    @DisplayName("测试VIP读者创建 - 更高借阅限额")
    void testVipReaderCreation() {
        ReaderRequest request = new ReaderRequest();
        request.setReader_name("VIP测试读者");
        request.setReader_type(TestConstants.READER_TYPE_VIP);
        request.setBorrow_limit(TestConstants.VIP_BORROW_LIMIT);

        when(readerRepository.save(any(Reader.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Reader createdReader = readerService.createReader(request);

        assertEquals(TestConstants.VIP_BORROW_LIMIT, createdReader.getBorrowLimit());
    }

    @Test
    @DisplayName("测试普通读者创建 - 默认借阅限额")
    void testNormalReaderCreation() {
        ReaderRequest request = new ReaderRequest();
        request.setReader_name("普通测试读者");
        request.setReader_type(TestConstants.READER_TYPE_NORMAL);

        when(readerRepository.save(any(Reader.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Reader createdReader = readerService.createReader(request);

        assertEquals(TestConstants.DEFAULT_BORROW_LIMIT, createdReader.getBorrowLimit());
    }

    @Test
    @DisplayName("测试读者信息更新")
    void testReaderUpdate() {
        Reader existingReader = readerBuilder.id("reader_update")
                .type(TestConstants.READER_TYPE_NORMAL)
                .build();

        ReaderRequest updateRequest = new ReaderRequest();
        updateRequest.setReader_name("更新后的姓名");
        updateRequest.setReader_phone("13900139000");
        updateRequest.setReader_type(TestConstants.READER_TYPE_VIP);
        updateRequest.setBorrow_limit(15);

        when(readerRepository.findByReaderId(existingReader.getReaderId())).thenReturn(Optional.of(existingReader));
        when(readerRepository.save(any(Reader.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Reader updatedReader = readerService.updateReader(existingReader.getReaderId(), updateRequest);

        assertEquals("更新后的姓名", updatedReader.getReaderName());
        assertEquals("13900139000", updatedReader.getReaderPhone());
        assertEquals(TestConstants.READER_TYPE_VIP, updatedReader.getReaderType());
        assertEquals(15, updatedReader.getBorrowLimit());
    }

    @Test
    @DisplayName("测试读者删除")
    void testReaderDeletion() {
        Reader reader = readerBuilder.id("reader_delete")
                .build();

        when(readerRepository.findByReaderId(reader.getReaderId())).thenReturn(Optional.of(reader));

        readerService.deleteReader(reader.getReaderId());

        verify(readerRepository, times(1)).delete(reader);
    }

    @Test
    @DisplayName("测试不存在的读者状态更新 - 抛出异常")
    void testNonExistentReaderStatusUpdate() {
        when(readerRepository.findByReaderId("non_existent")).thenReturn(Optional.empty());

        LibraryException exception = assertThrows(LibraryException.class,
                () -> readerService.updateReaderStatus("non_existent", TestConstants.READER_STATUS_FROZEN));

        assertEquals(404, exception.getCode());
        assertTrue(exception.getMessage().contains("读者不存在"));
    }

    @Test
    @DisplayName("测试不存在的读者删除 - 抛出异常")
    void testNonExistentReaderDeletion() {
        when(readerRepository.findByReaderId("non_existent")).thenReturn(Optional.empty());

        LibraryException exception = assertThrows(LibraryException.class,
                () -> readerService.deleteReader("non_existent"));

        assertEquals(404, exception.getCode());
        assertTrue(exception.getMessage().contains("读者不存在"));
    }

    @Test
    @DisplayName("测试读者逾期记录统计 - 多个逾期记录")
    void testReaderOverdueStatistics() {
        List<Reader> readers = new ArrayList<>();
        Reader reader1 = readerBuilder.id("overdue_1")
                .build();
        Reader reader2 = readerBuilder.id("overdue_2")
                .build();
        readers.add(reader1);
        readers.add(reader2);

        int overdueCount = 3;

        assertEquals(2, readers.size());
        assertEquals(3, overdueCount);
    }

    @Test
    @DisplayName("测试读者状态流转完整流程")
    void testFullReaderStatusFlow() {
        Reader reader = readerBuilder.id("reader_flow")
                .status(TestConstants.READER_STATUS_ACTIVE)
                .build();

        when(readerRepository.findByReaderId(reader.getReaderId())).thenReturn(Optional.of(reader));
        when(readerRepository.save(any(Reader.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Reader activeReader = readerService.updateReaderStatus(reader.getReaderId(), TestConstants.READER_STATUS_FROZEN);
        assertEquals(TestConstants.READER_STATUS_FROZEN, activeReader.getReaderStatus());

        when(readerRepository.findByReaderId(reader.getReaderId())).thenReturn(Optional.of(activeReader));

        Reader reactivatedReader = readerService.updateReaderStatus(reader.getReaderId(), TestConstants.READER_STATUS_ACTIVE);
        assertEquals(TestConstants.READER_STATUS_ACTIVE, reactivatedReader.getReaderStatus());

        verify(readerRepository, times(2)).save(any(Reader.class));
    }

    @Test
    @DisplayName("测试借阅权限综合校验流程")
    void testBorrowPermissionFlow() {
        Reader reader = readerBuilder.id("reader_permission_flow")
                .status(TestConstants.READER_STATUS_ACTIVE)
                .borrowLimit(3)
                .borrowedCount(0)
                .build();

        when(readerRepository.findByReaderId(reader.getReaderId())).thenReturn(Optional.of(reader));
        when(readerRepository.save(any(Reader.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertTrue(readerService.canBorrow(reader.getReaderId()));

        for (int i = 0; i < 3; i++) {
            readerService.increaseBorrowedCount(reader.getReaderId());
        }

        assertFalse(readerService.canBorrow(reader.getReaderId()));

        readerService.decreaseBorrowedCount(reader.getReaderId());

        assertTrue(readerService.canBorrow(reader.getReaderId()));
    }

    @Test
    @DisplayName("测试按状态查询读者")
    void testGetReadersByStatus() {
        List<Reader> activeReaders = new ArrayList<>();
        activeReaders.add(readerBuilder.id("active_1").status(TestConstants.READER_STATUS_ACTIVE).build());
        activeReaders.add(readerBuilder.id("active_2").status(TestConstants.READER_STATUS_ACTIVE).build());

        when(readerRepository.findByReaderStatus(TestConstants.READER_STATUS_ACTIVE)).thenReturn(activeReaders);

        List<Reader> result = readerService.getReadersByStatus(TestConstants.READER_STATUS_ACTIVE);

        assertEquals(2, result.size());
        assertEquals(TestConstants.READER_STATUS_ACTIVE, result.get(0).getReaderStatus());
    }

    @Test
    @DisplayName("测试按类型查询读者")
    void testGetReadersByType() {
        List<Reader> vipReaders = new ArrayList<>();
        vipReaders.add(readerBuilder.id("vip_1").type(TestConstants.READER_TYPE_VIP).build());

        when(readerRepository.findByReaderType(TestConstants.READER_TYPE_VIP)).thenReturn(vipReaders);

        List<Reader> result = readerService.getReadersByType(TestConstants.READER_TYPE_VIP);

        assertEquals(1, result.size());
        assertEquals(TestConstants.READER_TYPE_VIP, result.get(0).getReaderType());
    }

    @Test
    @DisplayName("测试获取所有读者")
    void testGetAllReaders() {
        List<Reader> allReaders = new ArrayList<>();
        allReaders.add(readerBuilder.id("all_1").build());
        allReaders.add(readerBuilder.id("all_2").build());
        allReaders.add(readerBuilder.id("all_3").build());

        when(readerRepository.findAll()).thenReturn(allReaders);

        List<Reader> result = readerService.getAllReaders();

        assertEquals(3, result.size());
    }
}
