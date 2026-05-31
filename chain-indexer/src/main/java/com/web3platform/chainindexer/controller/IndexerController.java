package com.web3platform.chainindexer.controller;

import com.web3platform.chaininteraction.model.EventLog;
import com.web3platform.chainindexer.dto.AbiRegisterRequest;
import com.web3platform.chainindexer.dto.DecodeEventRequest;
import com.web3platform.chainindexer.dto.DecodeTxRequest;
import com.web3platform.chainindexer.dto.IndexRangeRequest;
import com.web3platform.chainindexer.model.BlockIndexingTask;
import com.web3platform.chainindexer.model.DecodedEvent;
import com.web3platform.chainindexer.model.IndexedBlock;
import com.web3platform.chainindexer.service.AbiRepository;
import com.web3platform.chainindexer.service.BlockIndexerService;
import com.web3platform.chainindexer.service.IndexManager;
import com.web3platform.chainindexer.service.TransactionParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/indexer")
@RequiredArgsConstructor
public class IndexerController {

    private final BlockIndexerService blockIndexerService;
    private final IndexManager indexManager;
    private final AbiRepository abiRepository;
    private final TransactionParser transactionParser;

    @PostMapping("/block/{chainId}/{blockNumber}")
    public ResponseEntity<IndexedBlock> indexBlock(
            @PathVariable String chainId,
            @PathVariable Long blockNumber) {
        log.info("Request to index block {} on chain {}", blockNumber, chainId);
        IndexedBlock result = blockIndexerService.indexBlock(chainId, blockNumber);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/range/{chainId}")
    public ResponseEntity<BlockIndexingTask> indexRange(
            @PathVariable String chainId,
            @RequestBody IndexRangeRequest request) {
        log.info("Request to index range from {} to {} on chain {}",
                request.getFromBlock(), request.getToBlock(), chainId);

        BlockIndexingTask task = indexManager.createIndexingTask(
                chainId,
                request.getFromBlock(),
                request.getToBlock()
        );
        return ResponseEntity.ok(task);
    }

    @GetMapping("/task/{taskId}")
    public ResponseEntity<BlockIndexingTask> getTaskStatus(@PathVariable String taskId) {
        log.info("Request to get status for task {}", taskId);
        BlockIndexingTask task = indexManager.getTaskStatus(taskId);
        return ResponseEntity.ok(task);
    }

    @PostMapping("/task/pause/{taskId}")
    public ResponseEntity<Map<String, String>> pauseTask(@PathVariable String taskId) {
        log.info("Request to pause task {}", taskId);
        indexManager.pauseTask(taskId);
        Map<String, String> response = new HashMap<>();
        response.put("taskId", taskId);
        response.put("status", "PAUSED");
        return ResponseEntity.ok(response);
    }

    @PostMapping("/task/resume/{taskId}")
    public ResponseEntity<Map<String, String>> resumeTask(@PathVariable String taskId) {
        log.info("Request to resume task {}", taskId);
        indexManager.resumeTask(taskId);
        Map<String, String> response = new HashMap<>();
        response.put("taskId", taskId);
        response.put("status", "RUNNING");
        return ResponseEntity.ok(response);
    }

    @PostMapping("/abi/register")
    public ResponseEntity<Map<String, String>> registerAbi(@RequestBody AbiRegisterRequest request) {
        log.info("Request to register ABI for contract {}", request.getContractAddress());
        abiRepository.registerAbi(request.getContractAddress(), request.getAbiJson());
        Map<String, String> response = new HashMap<>();
        response.put("contractAddress", request.getContractAddress());
        response.put("status", "REGISTERED");
        return ResponseEntity.ok(response);
    }

    @PostMapping("/event/decode")
    public ResponseEntity<DecodedEvent> decodeEvent(@RequestBody DecodeEventRequest request) {
        log.info("Request to decode event for contract {}", request.getContractAddress());

        String abi = abiRepository.getAbi(request.getContractAddress());
        if (abi == null) {
            return ResponseEntity.notFound().build();
        }

        Map<String, String> abiMap = new HashMap<>();
        abiMap.put(request.getContractAddress(), abi);

        DecodedEvent decoded = transactionParser.decodeEventLog(request.getLog(), abiMap);
        if (decoded != null) {
            return ResponseEntity.ok(decoded);
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/tx/decode")
    public ResponseEntity<Map<String, String>> decodeTx(@RequestBody DecodeTxRequest request) {
        log.info("Request to decode transaction input for contract {}", request.getContractAddress());

        String abi = abiRepository.getAbi(request.getContractAddress());
        if (abi == null) {
            return ResponseEntity.notFound().build();
        }

        Map<String, String> abiMap = new HashMap<>();
        abiMap.put(request.getContractAddress(), abi);

        Map<String, String> decoded = abiRepository.decodeMethod(request.getInputData(), abi);
        return ResponseEntity.ok(decoded);
    }

    @PostMapping("/realtime/start/{chainId}")
    public ResponseEntity<Map<String, String>> startRealtimeIndexing(@PathVariable String chainId) {
        log.info("Request to start realtime indexing for chain {}", chainId);
        blockIndexerService.startRealtimeIndexing(chainId);
        Map<String, String> response = new HashMap<>();
        response.put("chainId", chainId);
        response.put("status", "STARTED");
        return ResponseEntity.ok(response);
    }

    @PostMapping("/realtime/stop/{chainId}")
    public ResponseEntity<Map<String, String>> stopRealtimeIndexing(@PathVariable String chainId) {
        log.info("Request to stop realtime indexing for chain {}", chainId);
        blockIndexerService.stopRealtimeIndexing(chainId);
        Map<String, String> response = new HashMap<>();
        response.put("chainId", chainId);
        response.put("status", "STOPPED");
        return ResponseEntity.ok(response);
    }
}
