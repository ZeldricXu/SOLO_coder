package com.supplychain.purchase.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class SupplierClientService {

    public void validateSupplier(String supplierId) {
        log.info("验证供应商资质: supplierId={}", supplierId);
    }
}
