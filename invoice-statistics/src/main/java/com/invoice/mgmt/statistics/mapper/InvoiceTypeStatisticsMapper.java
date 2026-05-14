package com.invoice.mgmt.statistics.mapper;

import com.invoice.mgmt.common.entity.InvoiceTypeStatistics;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface InvoiceTypeStatisticsMapper {
    int insert(InvoiceTypeStatistics stat);

    int update(InvoiceTypeStatistics stat);

    InvoiceTypeStatistics findByDayAndType(@Param("statDay") String statDay, @Param("invoiceType") String invoiceType);

    List<InvoiceTypeStatistics> findByDayRangeAndType(@Param("startDay") String startDay, @Param("endDay") String endDay, @Param("invoiceType") String invoiceType);

    List<InvoiceTypeStatistics> findByDayRange(@Param("startDay") String startDay, @Param("endDay") String endDay);

    int incrementIssueCount(@Param("statDay") String statDay, @Param("invoiceType") String invoiceType,
                            @Param("amount") java.math.BigDecimal amount, @Param("tax") java.math.BigDecimal tax);

    Integer getIssueCountByTypeAndDays(@Param("invoiceType") String invoiceType, @Param("days") int days);
}
