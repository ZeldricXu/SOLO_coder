package com.web3platform.txbuilder.controller;

import com.web3platform.txbuilder.model.*;
import com.web3platform.txbuilder.service.*;
import com.web3platform.txbuilder.util.NonceManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.web3j.crypto.RawTransaction;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/tx")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionBuilder transactionBuilder;
    private final TransactionSigner transactionSigner;
    private final TransactionEncoder transactionEncoder;
    private final GasOptimizationService gasOptimizationService;
    private final MultisigStrategyManager multisigStrategyManager;
    private final NonceManager nonceManager;

    @PostMapping("/build")
    public ResponseEntity<Map<String, Object>> buildTransaction(@RequestBody TransactionBuildRequest request) {
        log.info("Received build transaction request: chainId={}, from={}, to={}",
                request.getChainId(), request.getFromAddress(), request.getToAddress());

        try {
            Object typedTx = transactionBuilder.buildTypedTransaction(request);
            RawTransaction rawTx = (RawTransaction) typedTx;
            byte[] encoded = transactionEncoder.encode(rawTx);
            String rawTxHex = Numeric.toHexString(encoded);
            String txHash = transactionEncoder.hash(rawTx);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "rawTx", rawTxHex,
                    "txHash", txHash,
                    "nonce", rawTx.getNonce(),
                    "gasPrice", rawTx.getGasPrice(),
                    "gasLimit", rawTx.getGasLimit(),
                    "txType", request.getTxType() != null ? request.getTxType() : "LEGACY"
            ));
        } catch (Exception e) {
            log.error("Failed to build transaction", e);
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    @PostMapping("/sign")
    public ResponseEntity<SignResult> signTransaction(
            @RequestParam String rawTxHex,
            @RequestParam String privateKey,
            @RequestParam String chainId) {
        log.info("Received sign transaction request: chainId={}", chainId);

        try {
            RawTransaction rawTx = transactionEncoder.decode(rawTxHex);
            SignResult result = transactionSigner.signTransaction(rawTx, privateKey, chainId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to sign transaction", e);
            return ResponseEntity.badRequest().body(
                    SignResult.builder()
                            .signature("")
                            .signedTxHex("")
                            .txHash("")
                            .signerAddress("")
                            .build()
            );
        }
    }

    @PostMapping("/build-and-sign")
    public ResponseEntity<TransactionBuildResult> buildAndSign(
            @RequestBody TransactionBuildRequest request,
            @RequestParam String privateKey) {
        log.info("Received build and sign request: chainId={}, from={}",
                request.getChainId(), request.getFromAddress());

        try {
            Object typedTx = transactionBuilder.buildTypedTransaction(request);
            RawTransaction rawTx = (RawTransaction) typedTx;
            String rawTxHex = transactionEncoder.encodeToString(rawTx);

            SignResult signResult = transactionSigner.signTransaction(rawTx, privateKey, request.getChainId());

            TransactionBuildResult result = TransactionBuildResult.builder()
                    .signedTxHex(signResult.getSignedTxHex())
                    .txHash(signResult.getTxHash())
                    .rawTx(rawTxHex)
                    .gasUsed(rawTx.getGasLimit().longValue())
                    .build();

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to build and sign transaction", e);
            return ResponseEntity.badRequest().body(
                    TransactionBuildResult.builder()
                            .signedTxHex("")
                            .txHash("")
                            .rawTx("")
                            .gasUsed(0)
                            .build()
            );
        }
    }

    @PostMapping("/encode")
    public ResponseEntity<Map<String, Object>> encodeTransaction(@RequestBody TransactionBuildRequest request) {
        log.info("Received encode transaction request");

        try {
            RawTransaction rawTx = transactionBuilder.buildEvmTransaction(request);
            String encoded = transactionEncoder.encodeToString(rawTx);
            String hash = transactionEncoder.hash(rawTx);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "encodedTx", encoded,
                    "txHash", hash
            ));
        } catch (Exception e) {
            log.error("Failed to encode transaction", e);
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    @PostMapping("/decode")
    public ResponseEntity<Map<String, Object>> decodeTransaction(@RequestBody Map<String, String> body) {
        String signedTxHex = body.get("signedTxHex");
        log.info("Received decode transaction request");

        try {
            RawTransaction rawTx = transactionEncoder.decode(signedTxHex);
            String txType = transactionEncoder.getTransactionType(signedTxHex);
            BigInteger chainId = transactionEncoder.getChainIdFromSignedTx(signedTxHex);
            String signer = transactionSigner.recoverSigner(signedTxHex);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "nonce", rawTx.getNonce(),
                    "gasPrice", rawTx.getGasPrice(),
                    "gasLimit", rawTx.getGasLimit(),
                    "to", rawTx.getTo(),
                    "value", rawTx.getValue(),
                    "data", rawTx.getData(),
                    "chainId", chainId,
                    "txType", txType,
                    "signer", signer
            ));
        } catch (Exception e) {
            log.error("Failed to decode transaction", e);
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    @GetMapping("/hash/{signedTxHex}")
    public ResponseEntity<Map<String, Object>> getTransactionHash(@PathVariable String signedTxHex) {
        log.info("Received get transaction hash request");

        try {
            String hash = transactionEncoder.hash(signedTxHex);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "txHash", hash
            ));
        } catch (Exception e) {
            log.error("Failed to get transaction hash", e);
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    @PostMapping("/optimize")
    public ResponseEntity<Map<String, Object>> optimizeTransaction(
            @RequestBody Map<String, Object> body) {
        log.info("Received optimize transaction request");

        try {
            TransactionBuildRequest request = convertToRequest((Map<String, Object>) body.get("request"));
            GasOptimizationParams params = convertToParams((Map<String, Object>) body.get("params"));

            TransactionBuildRequest optimized = gasOptimizationService.optimizeTransaction(request, params);
            GasOptimizationParams suggestedParams = gasOptimizationService.suggestGasParams(
                    request.getChainId(), params.getSpeed());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "optimizedRequest", optimized,
                    "suggestedParams", suggestedParams
            ));
        } catch (Exception e) {
            log.error("Failed to optimize transaction", e);
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    @PostMapping("/strategy")
    public ResponseEntity<Map<String, Object>> createStrategy(@RequestBody MultisigStrategy strategy) {
        log.info("Received create strategy request: {}", strategy.getStrategyName());

        try {
            MultisigStrategy created = multisigStrategyManager.createStrategy(strategy);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "strategy", created
            ));
        } catch (Exception e) {
            log.error("Failed to create strategy", e);
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    @GetMapping("/strategy/{name}")
    public ResponseEntity<Map<String, Object>> getStrategy(@PathVariable String name) {
        log.info("Received get strategy request: {}", name);

        try {
            MultisigStrategy strategy = multisigStrategyManager.getStrategy(name);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "strategy", strategy
            ));
        } catch (Exception e) {
            log.error("Failed to get strategy", e);
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    @GetMapping("/strategy")
    public ResponseEntity<Map<String, Object>> listStrategies() {
        log.info("Received list strategies request");

        try {
            List<MultisigStrategy> strategies = multisigStrategyManager.listStrategies();
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "strategies", strategies,
                    "count", strategies.size()
            ));
        } catch (Exception e) {
            log.error("Failed to list strategies", e);
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    @GetMapping("/nonce/{chainId}/{address}")
    public ResponseEntity<Map<String, Object>> getNonce(
            @PathVariable String chainId,
            @PathVariable String address) {
        log.info("Received get nonce request: chainId={}, address={}", chainId, address);

        try {
            BigInteger nonce = nonceManager.getNonce(chainId, address);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "chainId", chainId,
                    "address", address,
                    "nonce", nonce
            ));
        } catch (Exception e) {
            log.error("Failed to get nonce", e);
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    @PostMapping("/gas/suggest")
    public ResponseEntity<Map<String, Object>> suggestGasParams(
            @RequestParam String chainId,
            @RequestParam(defaultValue = "NORMAL") String speed) {
        log.info("Received suggest gas params request: chainId={}, speed={}", chainId, speed);

        try {
            GasOptimizationParams params = gasOptimizationService.suggestGasParams(chainId, speed);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "chainId", chainId,
                    "params", params
            ));
        } catch (Exception e) {
            log.error("Failed to suggest gas params", e);
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    @PostMapping("/sign/keystore")
    public ResponseEntity<SignResult> signWithKeystore(
            @RequestBody Map<String, String> body) {
        log.info("Received sign with keystore request");

        try {
            String rawTxHex = body.get("rawTxHex");
            String keystoreJson = body.get("keystoreJson");
            String password = body.get("password");

            RawTransaction rawTx = transactionEncoder.decode(rawTxHex);
            SignResult result = transactionSigner.signWithKeystore(rawTx, keystoreJson, password);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to sign with keystore", e);
            return ResponseEntity.badRequest().body(
                    SignResult.builder()
                            .signature("")
                            .signedTxHex("")
                            .txHash("")
                            .signerAddress("")
                            .build()
            );
        }
    }

    @PostMapping("/sign/multisig")
    public ResponseEntity<SignResult> signMultisig(
            @RequestBody Map<String, String> body) {
        log.info("Received sign multisig request");

        try {
            String rawTxHex = body.get("rawTxHex");
            String ownerPrivateKey = body.get("ownerPrivateKey");

            RawTransaction rawTx = transactionEncoder.decode(rawTxHex);
            SignResult result = transactionSigner.signMultisig(rawTx, ownerPrivateKey);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to sign multisig transaction", e);
            return ResponseEntity.badRequest().body(
                    SignResult.builder()
                            .signature("")
                            .signedTxHex("")
                            .txHash("")
                            .signerAddress("")
                            .build()
            );
        }
    }

    @GetMapping("/recover/{signedTxHex}")
    public ResponseEntity<Map<String, Object>> recoverSigner(@PathVariable String signedTxHex) {
        log.info("Received recover signer request");

        try {
            String signer = transactionSigner.recoverSigner(signedTxHex);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "signerAddress", signer,
                    "signedTxHex", signedTxHex
            ));
        } catch (Exception e) {
            log.error("Failed to recover signer", e);
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    private TransactionBuildRequest convertToRequest(Map<String, Object> map) {
        return TransactionBuildRequest.builder()
                .chainId((String) map.get("chainId"))
                .fromAddress((String) map.get("fromAddress"))
                .toAddress((String) map.get("toAddress"))
                .value(map.get("value") != null ? new BigInteger(map.get("value").toString()) : BigInteger.ZERO)
                .data((String) map.get("data"))
                .gasLimit(map.get("gasLimit") != null ? Long.parseLong(map.get("gasLimit").toString()) : null)
                .gasPrice(map.get("gasPrice") != null ? new BigInteger(map.get("gasPrice").toString()) : null)
                .nonce(map.get("nonce") != null ? Long.parseLong(map.get("nonce").toString()) : null)
                .txType((String) map.get("txType"))
                .build();
    }

    private GasOptimizationParams convertToParams(Map<String, Object> map) {
        return GasOptimizationParams.builder()
                .speed(map.get("speed") != null ? (String) map.get("speed") : "NORMAL")
                .maxPriorityFee(map.get("maxPriorityFee") != null ?
                        new BigInteger(map.get("maxPriorityFee").toString()) : null)
                .maxFeePerGas(map.get("maxFeePerGas") != null ?
                        new BigInteger(map.get("maxFeePerGas").toString()) : null)
                .deadline(map.get("deadline") != null ?
                        Long.parseLong(map.get("deadline").toString()) : 0)
                .build();
    }
}
