package com.stockmgmt;

import com.stockmgmt.config.TestAsyncConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

@SpringBootTest
@ContextConfiguration(classes = {StockMgmtApplication.class, TestAsyncConfig.class})
@ActiveProfiles("test")
public abstract class BaseTest {

    @BeforeEach
    void setUpBase() {
    }

    @AfterEach
    void tearDownBase() {
    }
}
