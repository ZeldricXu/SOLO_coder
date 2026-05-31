package com.web3platform.catalog.interfaces.rest;

import com.web3platform.catalog.application.dto.AddDependencyRequest;
import com.web3platform.catalog.application.dto.DependencyResponse;
import com.web3platform.catalog.application.usecase.AddDependencyUseCase;
import com.web3platform.catalog.application.usecase.GetDependenciesUseCase;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/catalog/services/{serviceId}/dependencies")
public class DependencyController {
    private final AddDependencyUseCase addDependencyUseCase;
    private final GetDependenciesUseCase getDependenciesUseCase;

    public DependencyController(
        AddDependencyUseCase addDependencyUseCase,
        GetDependenciesUseCase getDependenciesUseCase
    ) {
        this.addDependencyUseCase = addDependencyUseCase;
        this.getDependenciesUseCase = getDependenciesUseCase;
    }

    @PostMapping
    public ResponseEntity<DependencyResponse> addDependency(
        @PathVariable UUID serviceId,
        @RequestBody AddDependencyRequest request
    ) {
        DependencyResponse response = addDependencyUseCase.execute(serviceId, request);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<List<DependencyResponse>> getDependencies(@PathVariable UUID serviceId) {
        List<DependencyResponse> dependencies = getDependenciesUseCase.getDependencies(serviceId);
        return ResponseEntity.ok(dependencies);
    }

    @GetMapping("/dependents")
    public ResponseEntity<List<DependencyResponse>> getDependents(@PathVariable UUID serviceId) {
        List<DependencyResponse> dependents = getDependenciesUseCase.getDependents(serviceId);
        return ResponseEntity.ok(dependents);
    }
}
