package com.solo.config;

import com.solo.config.entity.Config;
import com.solo.config.module.config.ConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class ConfigStatusMachineTest {

    @Autowired
    private ConfigService configService;

    @Test
    void testValidStatusTransitions() {
        assertTrue(configService.isValidStatusTransition(
                Config.ConfigStatus.DRAFT, Config.ConfigStatus.PUBLISHED));
        assertTrue(configService.isValidStatusTransition(
                Config.ConfigStatus.DRAFT, Config.ConfigStatus.ARCHIVED));
        assertTrue(configService.isValidStatusTransition(
                Config.ConfigStatus.PUBLISHED, Config.ConfigStatus.ARCHIVED));
        assertTrue(configService.isValidStatusTransition(
                Config.ConfigStatus.PUBLISHED, Config.ConfigStatus.DRAFT));
        assertTrue(configService.isValidStatusTransition(
                Config.ConfigStatus.ARCHIVED, Config.ConfigStatus.DRAFT));
    }

    @Test
    void testInvalidStatusTransitions() {
        assertFalse(configService.isValidStatusTransition(
                Config.ConfigStatus.PUBLISHED, Config.ConfigStatus.PUBLISHED));
        assertFalse(configService.isValidStatusTransition(
                Config.ConfigStatus.ARCHIVED, Config.ConfigStatus.PUBLISHED));
        assertFalse(configService.isValidStatusTransition(
                Config.ConfigStatus.ARCHIVED, Config.ConfigStatus.ARCHIVED));
        assertFalse(configService.isValidStatusTransition(
                Config.ConfigStatus.DRAFT, Config.ConfigStatus.DRAFT));
    }

    @Test
    void testNullStatusTransition() {
        assertFalse(configService.isValidStatusTransition(null, Config.ConfigStatus.PUBLISHED));
        assertFalse(configService.isValidStatusTransition(Config.ConfigStatus.DRAFT, null));
        assertFalse(configService.isValidStatusTransition(null, null));
    }
}
