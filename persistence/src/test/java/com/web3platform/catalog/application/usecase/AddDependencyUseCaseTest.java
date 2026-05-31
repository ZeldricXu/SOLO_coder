package com.web3platform.catalog.application.usecase;

import com.web3platform.catalog.application.dto.AddDependencyRequest;
import com.web3platform.catalog.application.dto.CreateServiceRequest;
import com.web3platform.catalog.domain.exception.CatalogException;
import com.web3platform.catalog.domain.model.DependencyType;
import com.web3platform.catalog.infrastructure.persistence.memory.InMemoryDependencyRepository;
import com.web3platform.catalog.infrastructure.persistence.memory.InMemoryServiceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AddDependencyUseCaseTest {
    private InMemoryServiceRepository serviceRepository;
    private InMemoryDependencyRepository dependencyRepository;
    private CreateServiceUseCase createServiceUseCase;
    private AddDependencyUseCase addDependencyUseCase;
    private GetDependenciesUseCase getDependenciesUseCase;

    private UUID serviceAId;
    private UUID serviceBId;

    @BeforeEach
    void setUp() {
        serviceRepository = new InMemoryServiceRepository();
        dependencyRepository = new InMemoryDependencyRepository();
        createServiceUseCase = new CreateServiceUseCase(serviceRepository);
        addDependencyUseCase = new AddDependencyUseCase(dependencyRepository, serviceRepository);
        getDependenciesUseCase = new GetDependenciesUseCase(dependencyRepository);

        CreateServiceRequest requestA = new CreateServiceRequest();
        requestA.setName("service-a");
        requestA.setOwner("owner");
        requestA.setTeam("team");
        serviceAId = createServiceUseCase.execute(requestA).getId();

        CreateServiceRequest requestB = new CreateServiceRequest();
        requestB.setName("service-b");
        requestB.setOwner("owner");
        requestB.setTeam("team");
        serviceBId = createServiceUseCase.execute(requestB).getId();
    }

    @Test
    void execute_shouldAddDependencySuccessfully() {
        AddDependencyRequest request = new AddDependencyRequest();
        request.setTargetId(serviceBId);
        request.setDepType(DependencyType.RUNTIME);
        request.setVersionConstraint("1.0.0");

        var result = addDependencyUseCase.execute(serviceAId, request);

        assertEquals(serviceAId, result.getSourceId());
        assertEquals(serviceBId, result.getTargetId());
        assertEquals(DependencyType.RUNTIME, result.getDepType());
        assertEquals("1.0.0", result.getVersionConstraint());

        assertEquals(1, getDependenciesUseCase.getDependencies(serviceAId).size());
        assertEquals(1, getDependenciesUseCase.getDependents(serviceBId).size());
    }

    @Test
    void execute_shouldThrowExceptionWhenSourceNotFound() {
        AddDependencyRequest request = new AddDependencyRequest();
        request.setTargetId(serviceBId);
        request.setDepType(DependencyType.RUNTIME);

        UUID nonExistentId = UUID.randomUUID();
        assertThrows(CatalogException.class, () -> addDependencyUseCase.execute(nonExistentId, request));
    }

    @Test
    void execute_shouldThrowExceptionWhenTargetNotFound() {
        AddDependencyRequest request = new AddDependencyRequest();
        request.setTargetId(UUID.randomUUID());
        request.setDepType(DependencyType.RUNTIME);

        assertThrows(CatalogException.class, () -> addDependencyUseCase.execute(serviceAId, request));
    }

    @Test
    void execute_shouldThrowExceptionWhenSelfDependency() {
        AddDependencyRequest request = new AddDependencyRequest();
        request.setTargetId(serviceAId);
        request.setDepType(DependencyType.RUNTIME);

        assertThrows(CatalogException.class, () -> addDependencyUseCase.execute(serviceAId, request));
    }
}
