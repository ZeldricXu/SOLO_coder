package com.homeservice.service;

import com.homeservice.service.LockManager;
import com.homeservice.service.LockManager.CustomerType;
import com.homeservice.service.LockManager.LockInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LockManager 人员锁定机制测试")
class LockManagerTest {

    private LockManager lockManager;

    @BeforeEach
    void setUp() {
        lockManager = new LockManager();
        lockManager.expireAllLocks();
    }

    @Test
    @DisplayName("测试人员预约前获取分布式锁的正确性")
    void testAcquireLockSuccess() {
        String staffId = "staff_001";
        String customerId = "customer_001";

        boolean result = lockManager.acquireLock(staffId, customerId, CustomerType.NORMAL);

        assertTrue(result, "锁获取应该成功");
        assertTrue(lockManager.isLocked(staffId), "人员应该被锁定");
        assertEquals(1, lockManager.getActiveLockCount(), "活动锁数量应为1");
    }

    @Test
    @DisplayName("测试同一客户重复获取锁成功")
    void testSameCustomerReacquireLock() {
        String staffId = "staff_001";
        String customerId = "customer_001";

        boolean firstResult = lockManager.acquireLock(staffId, customerId, CustomerType.NORMAL);
        boolean secondResult = lockManager.acquireLock(staffId, customerId, CustomerType.NORMAL);

        assertTrue(firstResult, "首次获取锁应该成功");
        assertTrue(secondResult, "同一客户重复获取锁应该成功");
    }

    @Test
    @DisplayName("测试并发预约时锁冲突处理 - 不同客户竞争同一人员")
    void testConcurrentLockConflict() {
        String staffId = "staff_001";
        String customerA = "customer_A";
        String customerB = "customer_B";

        boolean resultA = lockManager.acquireLock(staffId, customerA, CustomerType.NORMAL);
        boolean resultB = lockManager.acquireLock(staffId, customerB, CustomerType.NORMAL);

        assertTrue(resultA, "客户A应该获取锁成功");
        assertFalse(resultB, "客户B应该获取锁失败");

        LockInfo lockInfo = lockManager.getLockInfo(staffId);
        assertNotNull(lockInfo, "锁信息不应为空");
        assertEquals(customerA, lockInfo.getLockOwner(), "锁所有者应该是客户A");
    }

    @Test
    @DisplayName("测试VIP客户短超时")
    void testVIPCustomerShortTimeout() {
        String staffId = "staff_001";
        String customerId = "customer_vip";

        lockManager.acquireLock(staffId, customerId, CustomerType.VIP);

        LockInfo lockInfo = lockManager.getLockInfo(staffId);
        assertNotNull(lockInfo);
        assertEquals(3000, lockInfo.getTimeoutMs(), "VIP客户锁超时应为3秒");
        assertEquals("VIP", lockInfo.getCustomerType(), "客户类型应为VIP");
    }

    @Test
    @DisplayName("测试普通客户长超时")
    void testNormalCustomerLongTimeout() {
        String staffId = "staff_001";
        String customerId = "customer_normal";

        lockManager.acquireLock(staffId, customerId, CustomerType.NORMAL);

        LockInfo lockInfo = lockManager.getLockInfo(staffId);
        assertNotNull(lockInfo);
        assertEquals(30000, lockInfo.getTimeoutMs(), "普通客户锁超时应为30秒");
        assertEquals("NORMAL", lockInfo.getCustomerType(), "客户类型应为NORMAL");
    }

    @Test
    @DisplayName("测试自定义超时时间")
    void testCustomTimeout() {
        String staffId = "staff_001";
        String customerId = "customer_custom";
        long customTimeout = 15000;

        lockManager.acquireLock(staffId, customerId, customTimeout);

        LockInfo lockInfo = lockManager.getLockInfo(staffId);
        assertNotNull(lockInfo);
        assertEquals(customTimeout, lockInfo.getTimeoutMs(), "自定义超时时间应生效");
    }

    @Test
    @DisplayName("测试锁定释放与恢复的正确性 - 通过staffId释放")
    void testLockReleaseByStaffId() {
        String staffId = "staff_001";
        String customerId = "customer_001";

        lockManager.acquireLock(staffId, customerId, CustomerType.NORMAL);
        assertTrue(lockManager.isLocked(staffId), "获取锁后应该被锁定");

        lockManager.releaseLock(staffId);

        assertFalse(lockManager.isLocked(staffId), "释放锁后不应被锁定");
        assertNull(lockManager.getLockInfo(staffId), "锁信息应该为空");
    }

    @Test
    @DisplayName("测试锁定释放与恢复的正确性 - 通过customerId释放")
    void testLockReleaseByCustomerId() {
        String staffId = "staff_001";
        String ownerCustomer = "customer_owner";
        String otherCustomer = "customer_other";

        lockManager.acquireLock(staffId, ownerCustomer, CustomerType.NORMAL);
        assertTrue(lockManager.isLocked(staffId));

        lockManager.releaseLock(staffId, otherCustomer);
        assertTrue(lockManager.isLocked(staffId), "非锁所有者释放不应成功");

        lockManager.releaseLock(staffId, ownerCustomer);
        assertFalse(lockManager.isLocked(staffId), "锁所有者释放应该成功");
    }

    @Test
    @DisplayName("测试锁定超时后自动释放")
    void testLockExpiration() throws InterruptedException {
        String staffId = "staff_001";
        String customerId = "customer_001";
        long shortTimeout = 100;

        lockManager.acquireLock(staffId, customerId, shortTimeout);
        assertTrue(lockManager.isLocked(staffId), "刚获取锁时应该被锁定");

        Thread.sleep(shortTimeout + 10);

        assertFalse(lockManager.isLocked(staffId), "超时后应该自动释放");
        assertNull(lockManager.getLockInfo(staffId), "超时后锁信息应该被清除");
    }

    @Test
    @DisplayName("测试超时后其他客户可以获取锁")
    void testLockExpiredOtherCustomerCanAcquire() throws InterruptedException {
        String staffId = "staff_001";
        String customerA = "customer_A";
        String customerB = "customer_B";
        long shortTimeout = 100;

        lockManager.acquireLock(staffId, customerA, shortTimeout);

        Thread.sleep(shortTimeout + 10);

        boolean result = lockManager.acquireLock(staffId, customerB, CustomerType.NORMAL);
        assertTrue(result, "超时后其他客户应该可以获取锁");

        LockInfo lockInfo = lockManager.getLockInfo(staffId);
        assertEquals(customerB, lockInfo.getLockOwner(), "新的锁所有者应该是客户B");
    }

    @Test
    @DisplayName("测试多人锁定管理")
    void testMultipleStaffLocks() {
        String staff1 = "staff_001";
        String staff2 = "staff_002";
        String staff3 = "staff_003";
        String customerId = "customer_001";

        lockManager.acquireLock(staff1, customerId, CustomerType.NORMAL);
        lockManager.acquireLock(staff2, customerId, CustomerType.NORMAL);
        lockManager.acquireLock(staff3, customerId, CustomerType.NORMAL);

        assertEquals(3, lockManager.getActiveLockCount(), "应该有3个活动锁");
        assertTrue(lockManager.isLocked(staff1));
        assertTrue(lockManager.isLocked(staff2));
        assertTrue(lockManager.isLocked(staff3));
    }

    @Test
    @DisplayName("测试清空所有锁")
    void testExpireAllLocks() {
        String staff1 = "staff_001";
        String staff2 = "staff_002";
        String customerId = "customer_001";

        lockManager.acquireLock(staff1, customerId, CustomerType.NORMAL);
        lockManager.acquireLock(staff2, customerId, CustomerType.NORMAL);
        assertEquals(2, lockManager.getActiveLockCount());

        lockManager.expireAllLocks();

        assertEquals(0, lockManager.getActiveLockCount(), "所有锁应该被清空");
        assertFalse(lockManager.isLocked(staff1));
        assertFalse(lockManager.isLocked(staff2));
    }

    @Test
    @DisplayName("测试获取不存在的锁信息返回null")
    void testGetLockInfoNonExistent() {
        LockInfo lockInfo = lockManager.getLockInfo("non_existent_staff");
        assertNull(lockInfo, "不存在的锁信息应该返回null");
    }

    @Test
    @DisplayName("测试客户类型枚举值")
    void testCustomerTypeEnum() {
        assertEquals(3000, CustomerType.VIP.getTimeoutMs());
        assertEquals(30000, CustomerType.NORMAL.getTimeoutMs());
    }
}
