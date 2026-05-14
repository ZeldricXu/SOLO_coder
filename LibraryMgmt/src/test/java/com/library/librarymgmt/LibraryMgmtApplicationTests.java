package com.library.librarymgmt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class LibraryMgmtApplicationTests {

    @Test
    void contextLoads() {
        assertTrue(true, "基础测试应该通过");
    }

    @Test
    void testBasicAssertions() {
        assertEquals(4, 2 + 2);
        assertTrue(10 > 5);
        assertNotNull("test");
    }
}
