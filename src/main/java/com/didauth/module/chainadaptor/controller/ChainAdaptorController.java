package com.didauth.module.chainadaptor.controller;

import com.didauth.common.response.ApiResponse;
import com.didauth.core.entity.ChainRpcNode;
import com.didauth.module.chainadaptor.dto.SendTransactionRequest;
import com.didauth.module.chainadaptor.service.ChainAdaptorService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/chain")
@RequiredArgsConstructor
public class ChainAdaptorController {

    private final ChainAdaptorService chainAdaptorService;

    @GetMapping("/{chainType}/blockNumber")
    public Mono<ApiResponse<String>> getBlockNumber(@PathVariable String chainType) {
        return chainAdaptorService.getBlockNumber(chainType)
                .map(ApiResponse::success);
    }

    @GetMapping("/{chainType}/blocks/{blockNumber}")
    public Mono<ApiResponse<String>> getBlock(
            @PathVariable String chainType,
            @PathVariable String blockNumber,
            @RequestParam(defaultValue = "false") boolean fullTx) {
        return chainAdaptorService.getBlockByNumber(chainType, blockNumber, fullTx)
                .map(ApiResponse::success);
    }

    @GetMapping("/{chainType}/transactions/{txHash}")
    public Mono<ApiResponse<String>> getTransaction(@PathVariable String chainType, @PathVariable String txHash) {
        return chainAdaptorService.getTransactionByHash(chainType, txHash)
                .map(ApiResponse::success);
    }

    @GetMapping("/{chainType}/transactions/{txHash}/receipt")
    public Mono<ApiResponse<String>> getTransactionReceipt(@PathVariable String chainType, @PathVariable String txHash) {
        return chainAdaptorService.getTransactionReceipt(chainType, txHash)
                .map(ApiResponse::success);
    }

    @GetMapping("/{chainType}/addresses/{address}/balance")
    public Mono<ApiResponse<String>> getBalance(
            @PathVariable String chainType,
            @PathVariable String address,
            @RequestParam(required = false) String blockTag) {
        return chainAdaptorService.getBalance(chainType, address, blockTag)
                .map(ApiResponse::success);
    }

    @GetMapping("/{chainType}/addresses/{address}/nonce")
    public Mono<ApiResponse<String>> getNonce(@PathVariable String chainType, @PathVariable String address) {
        return chainAdaptorService.getNonce(chainType, address)
                .map(ApiResponse::success);
    }

    @PostMapping("/{chainType}/sendRawTransaction")
    public Mono<ApiResponse<String>> sendRawTransaction(@Valid @RequestBody SendTransactionRequest request) {
        return chainAdaptorService.sendRawTransaction(request)
                .map(ApiResponse::success);
    }

    @PostMapping("/{chainType}/call")
    public Mono<ApiResponse<String>> callRpc(
            @PathVariable String chainType,
            @RequestBody Map<String, Object> body) {
        String method = (String) body.get("method");
        List<Object> params = (List<Object>) body.get("params");
        return chainAdaptorService.call(chainType, method, params.toArray())
                .map(ApiResponse::success);
    }

    @GetMapping("/rpc-nodes")
    public Mono<ApiResponse<List<ChainRpcNode>>> listRpcNodes(
            @RequestParam(required = false) String chainType) {
        return chainAdaptorService.listRpcNodes(chainType)
                .map(ApiResponse::success);
    }

    @PostMapping("/rpc-nodes")
    public Mono<ApiResponse<String>> addRpcNode(@RequestBody ChainRpcNode node) {
        return chainAdaptorService.addRpcNode(node)
                .map(id -> ApiResponse.success(201, id));
    }

    @DeleteMapping("/rpc-nodes/{id}")
    public Mono<ApiResponse<Void>> deleteRpcNode(@PathVariable String id) {
        return chainAdaptorService.deleteRpcNode(id)
                .then(Mono.just(ApiResponse.success(null)));
    }

    @GetMapping("/{chainType}/chainId")
    public Mono<ApiResponse<String>> getChainId(@PathVariable String chainType) {
        return chainAdaptorService.getChainId(chainType)
                .map(ApiResponse::success);
    }
}
