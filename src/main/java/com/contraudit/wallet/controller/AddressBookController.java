package com.contraudit.wallet.controller;

import com.contraudit.common.ApiResponse;
import com.contraudit.wallet.dto.AddAddressBookRequest;
import com.contraudit.wallet.entity.AddressBook;
import com.contraudit.wallet.entity.AddressTag;
import com.contraudit.wallet.service.AddressBookService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/v1/address-book")
@RequiredArgsConstructor
public class AddressBookController {

    private final AddressBookService addressBookService;

    @PostMapping
    public Mono<ApiResponse<AddressBook>> addAddress(@Valid @RequestBody AddAddressBookRequest request) {
        return Mono.just(ApiResponse.created(addressBookService.addAddress(request)));
    }

    @GetMapping("/{id}")
    public Mono<ApiResponse<AddressBook>> getAddress(@PathVariable String id) {
        return Mono.just(ApiResponse.success(addressBookService.getAddress(id)));
    }

    @GetMapping
    public Mono<ApiResponse<List<AddressBook>>> listAddresses(
            @RequestParam(required = false) String chainType,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Boolean whitelist,
            @RequestParam(required = false) Boolean blacklist) {
        return Mono.just(ApiResponse.success(addressBookService.listAddresses(chainType, category, whitelist, blacklist)));
    }

    @DeleteMapping("/{id}")
    public Mono<ApiResponse<Void>> deleteAddress(@PathVariable String id) {
        addressBookService.deleteAddress(id);
        return Mono.just(ApiResponse.success());
    }

    @PostMapping("/{id}/tags")
    public Mono<ApiResponse<AddressTag>> addTag(
            @PathVariable String id,
            @RequestParam String tagName,
            @RequestParam(required = false) String tagValue) {
        return Mono.just(ApiResponse.created(addressBookService.addTag(id, tagName, tagValue)));
    }

    @GetMapping("/{id}/tags")
    public Mono<ApiResponse<List<AddressTag>>> getAddressTags(@PathVariable String id) {
        return Mono.just(ApiResponse.success(addressBookService.getAddressTags(id)));
    }

    @DeleteMapping("/tags/{tagId}")
    public Mono<ApiResponse<Void>> deleteTag(@PathVariable String tagId) {
        addressBookService.deleteTag(tagId);
        return Mono.just(ApiResponse.success());
    }
}
