package com.stockmgmt.controller;

import com.stockmgmt.common.Result;
import com.stockmgmt.dto.*;
import com.stockmgmt.service.InboundOutboundService;
import com.stockmgmt.service.LockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

@RestController
@RequestMapping("/api/v1/stock")
public class InboundOutboundController {

    private static final Logger logger = LoggerFactory.getLogger(InboundOutboundController.class);

    @Autowired
    private InboundOutboundService inboundOutboundService;

    @Autowired
    private LockService lockService;

    @PostMapping("/inbound")
    public Result<InboundResponse> inbound(@Valid @RequestBody InboundRequest request) {
        logger.info("入库操作，商品ID: {}, 数量: {}", request.getProductId(), request.getQuantity());
        InboundResponse response = inboundOutboundService.inbound(request);
        return Result.success(response);
    }

    @PostMapping("/outbound")
    public Result<OutboundResponse> outbound(@Valid @RequestBody OutboundRequest request) {
        logger.info("出库操作，商品ID: {}, 数量: {}", request.getProductId(), request.getQuantity());
        OutboundResponse response = inboundOutboundService.outbound(request);
        return Result.success(response);
    }

    @PostMapping("/transfer")
    public Result<TransferResponse> transfer(@Valid @RequestBody TransferRequest request) {
        logger.info("调拨操作，商品ID: {}, 数量: {}", request.getProductId(), request.getQuantity());
        TransferResponse response = inboundOutboundService.transfer(request);
        return Result.success(response);
    }

    @PostMapping("/lock")
    public Result<LockResponse> lockStock(@Valid @RequestBody LockRequest request) {
        logger.info("锁定库存，商品ID: {}, 数量: {}", request.getProductId(), request.getQuantity());
        LockResponse response = lockService.lockStock(request);
        return Result.success(response);
    }

    @PostMapping("/lock/{lockId}/unlock")
    public Result<Void> unlockStock(
            @PathVariable String lockId,
            @RequestParam(required = false) String operator,
            @RequestParam(required = false) String remark) {
        logger.info("解锁库存，lockId: {}", lockId);
        lockService.unlockStock(lockId, operator, remark);
        return Result.success();
    }

    @PostMapping("/lock/unlock-by-reference")
    public Result<Void> unlockStockByReference(
            @RequestParam String referenceNo,
            @RequestParam(required = false) String operator,
            @RequestParam(required = false) String remark) {
        logger.info("根据参考单号解锁库存，referenceNo: {}", referenceNo);
        lockService.unlockStockByReferenceNo(referenceNo, operator, remark);
        return Result.success();
    }
}
