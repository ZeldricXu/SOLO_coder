package com.solocoder.platform.inference.controller;

import com.solocoder.platform.common.model.ApiResponse;
import com.solocoder.platform.inference.model.InferenceRequest;
import com.solocoder.platform.inference.model.InferenceResponse;
import com.solocoder.platform.inference.model.ModelProvider;
import com.solocoder.platform.inference.service.InferenceRouterService;
import com.solocoder.platform.inference.service.ProviderRegistry;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/inference")
@RequiredArgsConstructor
public class InferenceGatewayController {

    private final InferenceRouterService routerService;
    private final ProviderRegistry providerRegistry;

    @PostMapping("/route")
    public ApiResponse<InferenceResponse> route(@Valid @RequestBody InferenceRequest request) {
        return ApiResponse.success(routerService.route(request));
    }

    @PostMapping("/route/fallback")
    public ApiResponse<InferenceResponse> routeWithFallback(@Valid @RequestBody InferenceRequest request) {
        return ApiResponse.success(routerService.routeWithFallback(request));
    }

    @PostMapping("/providers")
    public ApiResponse<Void> registerProvider(@Valid @RequestBody ModelProvider provider) {
        providerRegistry.register(provider);
        return ApiResponse.success();
    }

    @DeleteMapping("/providers/{providerId}")
    public ApiResponse<Void> unregisterProvider(@PathVariable String providerId) {
        providerRegistry.unregister(providerId);
        return ApiResponse.success();
    }

    @GetMapping("/providers")
    public ApiResponse<List<ModelProvider>> listProviders() {
        return ApiResponse.success(providerRegistry.getAllProviders());
    }

    @GetMapping("/providers/{providerId}")
    public ApiResponse<ModelProvider> getProvider(@PathVariable String providerId) {
        return providerRegistry.getProvider(providerId)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error(404, "Provider not found: " + providerId));
    }

    @PutMapping("/providers/{providerId}/status")
    public ApiResponse<Void> updateProviderStatus(@PathVariable String providerId,
                                                   @RequestParam ModelProvider.ProviderStatus status) {
        providerRegistry.updateStatus(providerId, status);
        return ApiResponse.success();
    }
}
