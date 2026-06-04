package com.battle.platform.netty;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BattlefieldPortAllocatorTest {

    @Test
    void testAllocatePort_Success() throws BattlefieldPortAllocator.PortAllocationException {
        BattlefieldPortAllocator allocator = new BattlefieldPortAllocator();
        int port = allocator.allocatePort();
        assertTrue(port >= 10000 && port <= 19999);
        assertTrue(allocator.isAllocated(port));
    }

    @Test
    void testReleasePort() throws BattlefieldPortAllocator.PortAllocationException {
        BattlefieldPortAllocator allocator = new BattlefieldPortAllocator();
        int port = allocator.allocatePort();
        assertTrue(allocator.isAllocated(port));

        allocator.releasePort(port);
        assertFalse(allocator.isAllocated(port));
    }

    @Test
    void testMultiplePorts_Unique() throws BattlefieldPortAllocator.PortAllocationException {
        BattlefieldPortAllocator allocator = new BattlefieldPortAllocator();

        int port1 = allocator.allocatePort();
        int port2 = allocator.allocatePort();
        int port3 = allocator.allocatePort();

        assertNotEquals(port1, port2);
        assertNotEquals(port2, port3);
        assertNotEquals(port1, port3);
    }

    @Test
    void testConcurrentAllocation() {
        BattlefieldPortAllocator allocator = new BattlefieldPortAllocator();

        assertDoesNotThrow(() -> {
            for (int i = 0; i < 10; i++) {
                allocator.allocatePort();
            }
        });
    }
}
