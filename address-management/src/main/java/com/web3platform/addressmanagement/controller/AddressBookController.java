package com.web3platform.addressmanagement.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.web3platform.addressmanagement.model.AddressBookEntryRequest;
import com.web3platform.addressmanagement.model.AddressBatchTagRequest;
import com.web3platform.addressmanagement.service.AddressBookService;
import com.web3platform.addressmanagement.service.AddressValidator;
import com.web3platform.persistence.model.entity.AddressEntry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/address-book")
public class AddressBookController {

    private final AddressBookService addressBookService;
    private final AddressValidator addressValidator;

    public AddressBookController(AddressBookService addressBookService, AddressValidator addressValidator) {
        this.addressBookService = addressBookService;
        this.addressValidator = addressValidator;
    }

    @PostMapping("/entry")
    public ResponseEntity<AddressEntry> addEntry(@RequestBody AddressBookEntryRequest request) {
        return ResponseEntity.ok(addressBookService.addEntry(request));
    }

    @GetMapping("/entry/{chainType}/{address}")
    public ResponseEntity<AddressEntry> getEntry(
            @PathVariable String chainType,
            @PathVariable String address) {
        return ResponseEntity.ok(addressBookService.getEntry(address, chainType));
    }

    @GetMapping("/list")
    public ResponseEntity<IPage<AddressEntry>> listEntries(
            @RequestParam(required = false) String chainType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(addressBookService.listEntries(chainType, page, size));
    }

    @PutMapping("/entry/{chainType}/{address}/label")
    public ResponseEntity<AddressEntry> updateLabel(
            @PathVariable String chainType,
            @PathVariable String address,
            @RequestBody Map<String, String> body) {
        String label = body.get("label");
        return ResponseEntity.ok(addressBookService.updateLabel(address, chainType, label));
    }

    @PostMapping("/entry/{chainType}/{address}/tag")
    public ResponseEntity<AddressEntry> addTag(
            @PathVariable String chainType,
            @PathVariable String address,
            @RequestBody Map<String, String> body) {
        String tag = body.get("tag");
        return ResponseEntity.ok(addressBookService.addTag(address, chainType, tag));
    }

    @DeleteMapping("/entry/{chainType}/{address}/tag/{tag}")
    public ResponseEntity<AddressEntry> removeTag(
            @PathVariable String chainType,
            @PathVariable String address,
            @PathVariable String tag) {
        return ResponseEntity.ok(addressBookService.removeTag(address, chainType, tag));
    }

    @PostMapping("/batch/tags")
    public ResponseEntity<Map<String, String>> batchUpdateTags(@RequestBody AddressBatchTagRequest request) {
        addressBookService.batchUpdateTags(request);
        return ResponseEntity.ok(Map.of("message", "Batch tags updated successfully"));
    }

    @GetMapping("/search/tag/{tag}")
    public ResponseEntity<List<AddressEntry>> searchByTag(@PathVariable String tag) {
        return ResponseEntity.ok(addressBookService.searchByTag(tag));
    }

    @GetMapping("/search/label")
    public ResponseEntity<List<AddressEntry>> searchByLabel(@RequestParam String keyword) {
        return ResponseEntity.ok(addressBookService.searchByLabel(keyword));
    }

    @DeleteMapping("/entry/{chainType}/{address}")
    public ResponseEntity<Map<String, String>> deleteEntry(
            @PathVariable String chainType,
            @PathVariable String address) {
        addressBookService.deleteEntry(address, chainType);
        return ResponseEntity.ok(Map.of("message", "Address entry deleted successfully"));
    }

    @GetMapping("/validate/{chainType}/{address}")
    public ResponseEntity<Map<String, Object>> validateAddress(
            @PathVariable String chainType,
            @PathVariable String address) {
        boolean valid = addressValidator.validate(address, chainType);
        String normalized = null;
        if (valid) {
            normalized = addressValidator.normalize(address, chainType);
        }
        return ResponseEntity.ok(Map.of(
                "valid", valid,
                "address", address,
                "chainType", chainType,
                "normalized", normalized
        ));
    }
}
