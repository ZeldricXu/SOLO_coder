package com.web3platform.catalog.application.usecase;

import com.web3platform.catalog.application.dto.CreateServiceRequest;
import com.web3platform.catalog.application.dto.ServiceResponse;
import com.web3platform.catalog.domain.exception.CatalogException;
import com.web3platform.catalog.infrastructure.persistence.memory.InMemoryServiceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class CreateServiceUseCaseTest {
    private InMemoryServiceRepository serviceRepository;
    private CreateServiceUseCase useCase;

    @BeforeEach
    void setUp() {
        serviceRepository = new InMemoryServiceRepository();
        useCase = new CreateServiceUseCase(serviceRepository);
    }

    @Test
    void execute_shouldCreateServiceSuccessfully() {
        CreateServiceRequest request = new CreateServiceRequest();
        request.setName("test-service");
        request.setDescription("Test Service");
        request.setLanguage("Java");
        request.setOwner("owner@example.com");
        request.setTeam("engineering");
        request.setRepositoryUrl("https://github.com/test/test-service");
        request.setApiDocUrl("https://api.example.com/docs");
        request.setVersion("1.0.0");
        request.setTags(Arrays.asList("backend", "api"));

        ServiceResponse response = useCase.execute(request);

        assertNotNull(response.getId());
        assertEquals("test-service", response.getName());
        assertEquals("Test Service", response.getDescription());
        assertEquals("Java", response.getLanguage());
        assertEquals("owner@example.com", response.getOwner());
        assertEquals("engineering", response.getTeam());
        assertEquals("https://github.com/test/test-service", response.getRepositoryUrl());
        assertEquals("https://api.example.com/docs", response.getApiDocUrl());
        assertEquals("1.0.0", response.getVersion());
        assertEquals(2, response.getTags().size());
        assertTrue(response.getTags().contains("backend"));
        assertTrue(response.getTags().contains("api"));

        assertTrue(serviceRepository.exists(response.getId()));
    }

    @Test
    void execute_shouldThrowExceptionWhenNameExists() {
        CreateServiceRequest request1 = new CreateServiceRequest();
        request1.setName("test-service");
        request1.setOwner("owner@example.com");
        request1.setTeam("engineering");
        useCase.execute(request1);

        CreateServiceRequest request2 = new CreateServiceRequest();
        request2.setName("test-service");
        request2.setOwner("another@example.com");
        request2.setTeam("platform");

        assertThrows(CatalogException.class, () -> useCase.execute(request2));
    }

    @Test
    void execute_shouldThrowExceptionWhenNameIsBlank() {
        CreateServiceRequest request = new CreateServiceRequest();
        request.setName("");
        request.setOwner("owner@example.com");
        request.setTeam("engineering");

        assertThrows(CatalogException.class, () -> useCase.execute(request));
    }

    @Test
    void execute_shouldThrowExceptionWhenOwnerIsBlank() {
        CreateServiceRequest request = new CreateServiceRequest();
        request.setName("test-service");
        request.setOwner("");
        request.setTeam("engineering");

        assertThrows(CatalogException.class, () -> useCase.execute(request));
    }

    @Test
    void execute_shouldThrowExceptionWhenTeamIsBlank() {
        CreateServiceRequest request = new CreateServiceRequest();
        request.setName("test-service");
        request.setOwner("owner@example.com");
        request.setTeam("");

        assertThrows(CatalogException.class, () -> useCase.execute(request));
    }
}
