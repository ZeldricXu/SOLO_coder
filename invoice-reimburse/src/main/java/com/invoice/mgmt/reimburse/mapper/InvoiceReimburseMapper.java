package com.invoice.mgmt.reimburse.mapper;

import com.invoice.mgmt.common.entity.InvoiceReimburse;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface InvoiceReimburseMapper {
    int insert(InvoiceReimburse reimburse);

    int update(InvoiceReimburse reimburse);

    InvoiceReimburse findById(@Param("reimburseId") String reimburseId);

    List<InvoiceReimburse> findByInvoiceId(@Param("invoiceId") String invoiceId);

    List<InvoiceReimburse> findByUser(@Param("reimburseUser") String reimburseUser);

    List<InvoiceReimburse> findByStatus(@Param("reimburseStatus") String reimburseStatus);

    int updateStatus(@Param("reimburseId") String reimburseId,
                     @Param("status") String status,
                     @Param("approver") String approver,
                     @Param("remark") String remark);
}
