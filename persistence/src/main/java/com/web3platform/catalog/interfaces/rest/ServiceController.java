package com.web3platform.catalog.interfaces.rest;

import com.web3platform.catalog.application.dto.CreateServiceRequest;
import com.web3platform.catalog.application.dto.PagedResult;
import com.web3platform.catalog.application.dto.ServiceResponse;
import com.web3platform.catalog.application.dto.ServiceSearchRequest;
import com.web3platform.catalog.application.dto.UpdateServiceRequest;
import com.web3platform.catalog.application.usecase.CreateServiceUseCase;
import com.web3platform.catalog.application.usecase.DeleteServiceUseCase;
import com.web3platform.catalog.application.usecase.GetServiceUseCase;
import com.web3platform.catalog.application.usecase.SearchServicesUseCase;
import com.web3platform.catalog.application.usecase.UpdateServiceUseCase;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/catalog/services")
public class ServiceController {
    private final CreateServiceUseCase createServiceUseCase;
    private final GetServiceUseCase getServiceUseCase;
    private final UpdateServiceUseCase updateServiceUseCase;
    private final DeleteServiceUseCase deleteServiceUseCase;
    private final SearchServicesUseCase searchServicesUseCase;

    public ServiceController(
        CreateServiceUseCase createServiceUseCase,
        GetServiceUseCase getServiceUseCase,
        UpdateServiceUseCase updateServiceUseCase,
        DeleteServiceUseCase deleteServiceUseCase,
        SearchServicesUseCase searchServicesUseCase
    ) {
        this.createServiceUseCase = createServiceUseCase;
        this.getServiceUseCase = getServiceUseCase;
        this.updateServiceUseCase = updateServiceUseCase;
        this.deleteServiceUseCase = deleteServiceUseCase;
        this.searchServicesUseCase = searchServicesUseCase;
    }

    @PostMapping
    public ResponseEntity<ServiceResponse> create(@RequestBody CreateServiceRequest request) {
        ServiceResponse response = createServiceUseCase.execute(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ServiceResponse> getById(@PathVariable UUID id) {
        ServiceResponse response = getServiceUseCase.execute(id);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ServiceResponse> update(
        @PathVariable UUID id,
        @RequestBody UpdateServiceRequest request
    ) {
        ServiceResponse response = updateServiceUseCase.execute(id, request);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        deleteServiceUseCase.execute(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/search")
    public ResponseEntity<PagedResult<ServiceResponse>> search(@RequestBody ServiceSearchRequest request) {
        PagedResult<ServiceResponse> result = searchServicesUseCase.execute(request);
        return ResponseEntity.ok(result);
    }

    @GetMapping
    public ResponseEntity<PagedResult<ServiceResponse>> list(
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) String language,
        @RequestParam(required = false) String team,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int pageSize
    ) {
        ServiceSearchRequest request = new ServiceSearchRequest();
        request.setKeyword(keyword);
        request.setLanguage(language);
        request.setTeam(team);
        request.setPage(page);
        request.setPageSize(pageSize);
        PagedResult<ServiceResponse> result = searchServicesUseCase.execute(request);
        return ResponseEntity.ok(result);
    }
}
