package com.solocoder.platform.gas.estimator.adapter.rest;

import com.solocoder.platform.gas.estimator.adapter.dto.GasEstimationRequestDto;
import com.solocoder.platform.gas.estimator.adapter.dto.GasEstimationResponseDto;
import com.solocoder.platform.gas.estimator.application.service.GasEstimationApplicationService;
import com.solocoder.platform.gas.estimator.domain.model.GasEstimation;
import com.solocoder.platform.persistence.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/gas")
@RequiredArgsConstructor
public class GasEstimationController {

    private final GasEstimationApplicationService gasEstimationApplicationService;

    @PostMapping("/estimate")
    public ResponseEntity<ApiResponse<GasEstimationResponseDto>> estimateGas(
            @Valid @RequestBody GasEstimationRequestDto request) {
        GasEstimation estimation = gasEstimationApplicationService.estimateGas(
                request.getChainId(),
                request.getNetwork(),
                request.getTimestamp(),
                request.getSignature());
        return ResponseEntity.ok(ApiResponse.success(toResponseDto(estimation)));
    }

    @GetMapping("/{estimationId}")
    public ResponseEntity<ApiResponse<GasEstimationResponseDto>> getEstimation(
            @PathVariable String estimationId) {
        GasEstimation estimation = gasEstimationApplicationService.getEstimation(estimationId);
        return ResponseEntity.ok(ApiResponse.success(toResponseDto(estimation)));
    }

    @GetMapping("/chain/{chainId}/recent")
    public ResponseEntity<ApiResponse<List<GasEstimationResponseDto>>> getRecentEstimations(
            @PathVariable String chainId,
            @RequestParam(defaultValue = "10") int limit) {
        List<GasEstimation> estimations = gasEstimationApplicationService.getRecentEstimations(chainId, limit);
        List<GasEstimationResponseDto> dtos = estimations.stream()
                .map(this::toResponseDto)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(dtos));
    }

    private GasEstimationResponseDto toResponseDto(GasEstimation estimation) {
        return GasEstimationResponseDto.builder()
                .chainId(estimation.getChainId())
                .network(estimation.getNetwork())
                .latestBlock(estimation.getLatestBlock())
                .timestamp(estimation.getTimestamp())
                .estimationId(estimation.getEstimationId())
                .baseFee(estimation.getBaseFee())
                .gasPrices(GasEstimationResponseDto.GasPriceLevelDto.builder()
                        .low(estimation.getGasPrices().getLow())
                        .medium(estimation.getGasPrices().getMedium())
                        .high(estimation.getGasPrices().getHigh())
                        .build())
                .priorityFees(GasEstimationResponseDto.PriorityFeeLevelDto.builder()
                        .low(estimation.getPriorityFees().getLow())
                        .medium(estimation.getPriorityFees().getMedium())
                        .high(estimation.getPriorityFees().getHigh())
                        .build())
                .networkStatus(GasEstimationResponseDto.NetworkStatusDto.builder()
                        .pendingTransactions(estimation.getNetworkStatus().getPendingTransactions())
                        .blockGasUsed(estimation.getNetworkStatus().getBlockGasUsed())
                        .blockGasLimit(estimation.getNetworkStatus().getBlockGasLimit())
                        .gasUtilization(estimation.getNetworkStatus().getGasUtilization())
                        .congestionLevel(estimation.getNetworkStatus().getCongestionLevel().name())
                        .build())
                .build();
    }
}
