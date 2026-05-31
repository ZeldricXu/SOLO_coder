package com.web3platform.catalog.application.usecase;

import com.web3platform.catalog.application.dto.PagedResult;
import com.web3platform.catalog.application.dto.ServiceResponse;
import com.web3platform.catalog.application.dto.ServiceSearchRequest;
import com.web3platform.catalog.domain.model.ServiceEntry;
import com.web3platform.catalog.domain.model.ServiceStatus;
import com.web3platform.catalog.infrastructure.persistence.memory.InMemoryServiceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SearchServicesUseCaseTest {
    private InMemoryServiceRepository serviceRepository;
    private SearchServicesUseCase useCase;

    @BeforeEach
    void setUp() {
        serviceRepository = new InMemoryServiceRepository();
        useCase = new SearchServicesUseCase(serviceRepository);
        createTestData();
    }

    private void createTestData() {
        ServiceEntry service1 = ServiceEntry.create(
            "user-service", "User management service", "Java",
            "alice", "backend", "https://github.com/user-service", "1.0.0"
        );
        service1.addTag("api");
        service1.activate();
        serviceRepository.save(service1);

        ServiceEntry service2 = ServiceEntry.create(
            "order-service", "Order processing service", "Java",
            "bob", "backend", "https://github.com/order-service", "2.0.0"
        );
        service2.addTag("api");
        service2.addTag("payment");
        service2.activate();
        serviceRepository.save(service2);

        ServiceEntry service3 = ServiceEntry.create(
            "notification-service", "Notification service", "Go",
            "charlie", "platform", "https://github.com/notification-service", "1.5.0"
        );
        service3.addTag("messaging");
        serviceRepository.save(service3);
    }

    @Test
    void execute_shouldReturnAllServicesWhenNoFilters() {
        ServiceSearchRequest request = new ServiceSearchRequest();
        PagedResult<ServiceResponse> result = useCase.execute(request);

        assertEquals(3, result.getTotal());
        assertEquals(3, result.getItems().size());
        assertEquals(0, result.getPage());
        assertEquals(20, result.getPageSize());
        assertEquals(1, result.getTotalPages());
    }

    @Test
    void execute_shouldFilterByKeyword() {
        ServiceSearchRequest request = new ServiceSearchRequest();
        request.setKeyword("order");

        PagedResult<ServiceResponse> result = useCase.execute(request);

        assertEquals(1, result.getTotal());
        assertEquals("order-service", result.getItems().get(0).getName());
    }

    @Test
    void execute_shouldFilterByLanguage() {
        ServiceSearchRequest request = new ServiceSearchRequest();
        request.setLanguage("Java");

        PagedResult<ServiceResponse> result = useCase.execute(request);

        assertEquals(2, result.getTotal());
        assertTrue(result.getItems().stream()
            .allMatch(s -> s.getLanguage().equals("Java")));
    }

    @Test
    void execute_shouldFilterByTeam() {
        ServiceSearchRequest request = new ServiceSearchRequest();
        request.setTeam("platform");

        PagedResult<ServiceResponse> result = useCase.execute(request);

        assertEquals(1, result.getTotal());
        assertEquals("notification-service", result.getItems().get(0).getName());
    }

    @Test
    void execute_shouldFilterByStatus() {
        ServiceSearchRequest request = new ServiceSearchRequest();
        request.setStatus(ServiceStatus.ACTIVE);

        PagedResult<ServiceResponse> result = useCase.execute(request);

        assertEquals(2, result.getTotal());
        assertTrue(result.getItems().stream()
            .allMatch(s -> s.getStatus() == ServiceStatus.ACTIVE));
    }

    @Test
    void execute_shouldFilterByTags() {
        ServiceSearchRequest request = new ServiceSearchRequest();
        request.setTags(List.of("api"));

        PagedResult<ServiceResponse> result = useCase.execute(request);

        assertEquals(2, result.getTotal());
    }

    @Test
    void execute_shouldSupportPagination() {
        ServiceSearchRequest request = new ServiceSearchRequest();
        request.setPage(0);
        request.setPageSize(2);

        PagedResult<ServiceResponse> result = useCase.execute(request);

        assertEquals(3, result.getTotal());
        assertEquals(2, result.getItems().size());
        assertEquals(2, result.getTotalPages());
    }

    @Test
    void execute_shouldCombineMultipleFilters() {
        ServiceSearchRequest request = new ServiceSearchRequest();
        request.setLanguage("Java");
        request.setStatus(ServiceStatus.ACTIVE);

        PagedResult<ServiceResponse> result = useCase.execute(request);

        assertEquals(2, result.getTotal());
        assertTrue(result.getItems().stream()
            .allMatch(s -> s.getLanguage().equals("Java") && s.getStatus() == ServiceStatus.ACTIVE));
    }
}
