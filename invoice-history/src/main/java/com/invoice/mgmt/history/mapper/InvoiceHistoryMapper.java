package com.invoice.mgmt.history.mapper;

import com.invoice.mgmt.common.entity.InvoiceHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface InvoiceHistoryMapper {
    int insert(InvoiceHistory history);

    List<InvoiceHistory> findByInvoiceId(@Param("invoiceId") String invoiceId);

    List<InvoiceHistory> findByActionType(@Param("actionType") String actionType);

    List<InvoiceHistory> findByOperator(@Param("operator") String operator);

    List<InvoiceHistory> findByTimeRange(@Param("start") Instant start, @Param("end") Instant end);
}
