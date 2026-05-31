package com.web3platform.catalog.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ServiceEntryTest {
    @Test
    void create_shouldInitializeWithDefaultValues() {
        ServiceEntry service = ServiceEntry.create(
            "test-service",
            "Test Description",
            "Java",
            "owner@example.com",
            "engineering",
            "https://github.com/test/test-service",
            "1.0.0"
        );

        assertNotNull(service.getId());
        assertEquals("test-service", service.getName());
        assertEquals(ServiceStatus.DEVELOPMENT, service.getStatus());
        assertNotNull(service.getCreatedAt());
        assertNotNull(service.getUpdatedAt());
        assertTrue(service.getTags().isEmpty());
    }

    @Test
    void update_shouldUpdateFieldsAndTimestamp() {
        ServiceEntry service = ServiceEntry.create(
            "test-service",
            "Old Description",
            "Java",
            "owner@example.com",
            "engineering",
            "https://github.com/test/test-service",
            "1.0.0"
        );

        service.update(
            "test-service",
            "New Description",
            "Java",
            "new-owner@example.com",
            "platform",
            "https://github.com/test/test-service",
            "https://api.example.com/docs",
            ServiceStatus.ACTIVE,
            "2.0.0"
        );

        assertEquals("New Description", service.getDescription());
        assertEquals("new-owner@example.com", service.getOwner());
        assertEquals("platform", service.getTeam());
        assertEquals("https://api.example.com/docs", service.getApiDocUrl());
        assertEquals(ServiceStatus.ACTIVE, service.getStatus());
        assertEquals("2.0.0", service.getVersion());
    }

    @Test
    void addTag_shouldAddUniqueTags() {
        ServiceEntry service = ServiceEntry.create(
            "test-service", "desc", "Java", "owner", "team", "repo", "1.0.0"
        );

        service.addTag("backend");
        service.addTag("api");
        service.addTag("backend");

        assertEquals(2, service.getTags().size());
        assertTrue(service.getTags().contains("backend"));
        assertTrue(service.getTags().contains("api"));
    }

    @Test
    void removeTag_shouldRemoveTag() {
        ServiceEntry service = ServiceEntry.create(
            "test-service", "desc", "Java", "owner", "team", "repo", "1.0.0"
        );

        service.addTag("backend");
        service.addTag("api");
        service.removeTag("backend");

        assertEquals(1, service.getTags().size());
        assertFalse(service.getTags().contains("backend"));
        assertTrue(service.getTags().contains("api"));
    }

    @Test
    void deprecate_shouldChangeStatusToDeprecated() {
        ServiceEntry service = ServiceEntry.create(
            "test-service", "desc", "Java", "owner", "team", "repo", "1.0.0"
        );
        service.activate();
        assertEquals(ServiceStatus.ACTIVE, service.getStatus());

        service.deprecate();
        assertEquals(ServiceStatus.DEPRECATED, service.getStatus());
    }

    @Test
    void activate_shouldChangeStatusToActive() {
        ServiceEntry service = ServiceEntry.create(
            "test-service", "desc", "Java", "owner", "team", "repo", "1.0.0"
        );
        assertEquals(ServiceStatus.DEVELOPMENT, service.getStatus());

        service.activate();
        assertEquals(ServiceStatus.ACTIVE, service.getStatus());
    }
}
