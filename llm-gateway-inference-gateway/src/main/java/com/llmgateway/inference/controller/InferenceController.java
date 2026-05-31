package com.llmgateway.inference.controller;

import com.llmgateway.common.api.R;
import com.llmgateway.inference.dto.InferenceDTO;
import com.llmgateway.inference.dto.InferenceResponse;
import com.llmgateway.inference.entity.InferenceRequest;
import com.llmgateway.inference.service.InferenceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/inference")
@RequiredArgsConstructor
public class InferenceController {

    private final InferenceService inferenceService;

    @PostMapping("/chat")
    public R<InferenceResponse> chat(@Valid @RequestBody InferenceDTO dto) {
        return R.success(inferenceService.inference(dto));
    }

    @GetMapping("/requests/{requestId}")
    public R<InferenceRequest> getRequest(@PathVariable String requestId) {
        return R.success(inferenceService.getRequest(requestId));
    }
}
