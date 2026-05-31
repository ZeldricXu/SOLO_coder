package com.nftindexer.modules.address.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nftindexer.common.ApiResponse;
import com.nftindexer.common.PageResult;
import com.nftindexer.entity.AddressBook;
import com.nftindexer.modules.address.dto.AddressBookRequest;
import com.nftindexer.modules.address.dto.AddressDeriveRequest;
import com.nftindexer.modules.address.dto.HDWalletCreateRequest;
import com.nftindexer.modules.address.service.AddressService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/addresses")
@RequiredArgsConstructor
public class AddressController {

    private final AddressService addressService;

    @PostMapping("/hdwallet")
    public Mono<ApiResponse<Map<String, Object>>> createHDWallet(
            @RequestBody(required = false) HDWalletCreateRequest request) {
        if (request == null) {
            request = new HDWalletCreateRequest();
        }
        return addressService.createHDWallet(request)
                .map(result -> ApiResponse.created(result));
    }

    @PostMapping("/derive")
    public Mono<ApiResponse<Map<String, Object>>> deriveAddress(
            @Valid @RequestBody AddressDeriveRequest request) {
        return addressService.deriveAddress(request)
                .map(result -> ApiResponse.created(result));
    }

    @PostMapping("/book")
    public Mono<ApiResponse<AddressBook>> addAddressBook(
            @Valid @RequestBody AddressBookRequest request) {
        return addressService.addAddressBook(request)
                .map(address -> ApiResponse.created(address));
    }

    @GetMapping("/book/{entryId}")
    public Mono<ApiResponse<AddressBook>> getAddressBook(@PathVariable String entryId) {
        return addressService.getAddressBook(entryId)
                .map(ApiResponse::success);
    }

    @GetMapping("/book/search")
    public Mono<ApiResponse<AddressBook>> getAddressByAddress(
            @RequestParam String address,
            @RequestParam String chainId) {
        return addressService.getAddressByAddress(address, chainId)
                .map(ApiResponse::success);
    }

    @GetMapping("/book")
    public Mono<ApiResponse<PageResult<AddressBook>>> listAddressBook(
            @RequestParam(required = false) String chainId,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String label,
            @RequestParam(required = false) String tag,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return addressService.listAddressBook(chainId, category, label, tag, pageNum, pageSize)
                .map(page -> ApiResponse.success(new PageResult<>(
                        page.getRecords(),
                        page.getTotal(),
                        page.getCurrent(),
                        page.getSize()
                )));
    }

    @PutMapping("/book/{entryId}")
    public Mono<ApiResponse<AddressBook>> updateAddressBook(
            @PathVariable String entryId,
            @Valid @RequestBody AddressBookRequest request) {
        return addressService.updateAddressBook(entryId, request)
                .map(ApiResponse::success);
    }

    @DeleteMapping("/book/{entryId}")
    public Mono<ApiResponse<Void>> deleteAddressBook(@PathVariable String entryId) {
        return addressService.deleteAddressBook(entryId)
                .map(v -> ApiResponse.success(null));
    }

    @GetMapping("/tags")
    public Mono<ApiResponse<List<String>>> getAddressTags() {
        return addressService.getAddressTags()
                .map(ApiResponse::success);
    }

    @GetMapping("/stats/by-chain")
    public Mono<ApiResponse<Map<String, Long>>> getAddressByChainStats() {
        return addressService.getAddressByChainStats()
                .map(ApiResponse::success);
    }
}
