package com.web3platform.addressmanagement.controller;

import com.web3platform.addressmanagement.model.AddressDeriveRequest;
import com.web3platform.addressmanagement.model.AddressResponse;
import com.web3platform.addressmanagement.model.HdWalletCreateRequest;
import com.web3platform.addressmanagement.model.HdWalletResponse;
import com.web3platform.addressmanagement.service.AddressDeriver;
import com.web3platform.addressmanagement.service.HdWalletService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/hd-wallet")
public class HdWalletController {

    private final HdWalletService hdWalletService;
    private final AddressDeriver addressDeriver;

    public HdWalletController(HdWalletService hdWalletService, AddressDeriver addressDeriver) {
        this.hdWalletService = hdWalletService;
        this.addressDeriver = addressDeriver;
    }

    @PostMapping("/create")
    public ResponseEntity<HdWalletResponse> createWallet(@RequestBody HdWalletCreateRequest request) {
        HdWalletResponse response = hdWalletService.createWallet(request.getMnemonic(), request.getPassword());

        if (request.getChainType() != null && !request.getChainType().isEmpty() && request.getAddressCount() > 0) {
            addressDeriver.deriveBatch(
                    response.getWalletId(),
                    request.getChainType(),
                    request.getAccountIndex(),
                    request.getAddressCount()
            );
        }

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{walletId}")
    public ResponseEntity<HdWalletResponse> getWallet(@PathVariable String walletId) {
        return ResponseEntity.ok(hdWalletService.getWallet(walletId));
    }

    @GetMapping("/list")
    public ResponseEntity<List<HdWalletResponse>> listWallets() {
        return ResponseEntity.ok(hdWalletService.listWallets());
    }

    @DeleteMapping("/{walletId}")
    public ResponseEntity<Map<String, String>> deleteWallet(@PathVariable String walletId) {
        hdWalletService.deleteWallet(walletId);
        return ResponseEntity.ok(Map.of("message", "Wallet deleted successfully"));
    }

    @PostMapping("/derive")
    public ResponseEntity<AddressResponse> deriveAddress(@RequestBody AddressDeriveRequest request) {
        AddressResponse response = addressDeriver.deriveAddress(
                request.getWalletId(),
                request.getChainType(),
                request.getPath(),
                request.getIndex()
        );
        return ResponseEntity.ok(response);
    }

    @PostMapping("/derive/batch")
    public ResponseEntity<List<AddressResponse>> deriveBatch(@RequestBody AddressDeriveRequest request) {
        List<AddressResponse> responses = addressDeriver.deriveBatch(
                request.getWalletId(),
                request.getChainType(),
                request.getIndex(),
                request.getCount()
        );
        return ResponseEntity.ok(responses);
    }
}
