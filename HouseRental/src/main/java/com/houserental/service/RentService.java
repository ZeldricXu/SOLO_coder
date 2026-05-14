package com.houserental.service;

import com.houserental.dto.PaymentDTO;
import com.houserental.entity.Contract;
import com.houserental.entity.Payment;
import com.houserental.exception.HouseRentalException;
import com.houserental.repository.PaymentRepository;
import com.houserental.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Service
public class RentService {

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private ContractService contractService;

    @Autowired
    private LandlordService landlordService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private StatisticsService statisticsService;

    private String getCurrentPeriod() {
        return LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
    }

    @Transactional
    public Payment createPendingPayment(Contract contract) {
        String paymentPeriod = getCurrentPeriod();

        Optional<Payment> existing = paymentRepository.findByContractIdAndPeriod(
                contract.getContractId(), paymentPeriod);
        if (existing.isPresent()) {
            return existing.get();
        }

        Payment payment = new Payment();
        payment.setPaymentId(IdGenerator.generatePaymentId());
        payment.setContractId(contract.getContractId());
        payment.setTenantId(contract.getTenantId());
        payment.setPaymentAmount(contract.getContractRent());
        payment.setPaymentPeriod(paymentPeriod);
        payment.setPaymentStatus("pending");
        payment.setPaymentMethod(null);

        Payment saved = paymentRepository.save(payment);

        historyService.recordPaymentHistory(
                saved.getPaymentId(),
                "CREATE",
                "创建租金支付记录，金额：" + contract.getContractRent() + "，周期：" + paymentPeriod,
                contract.getHouseId(),
                contract.getTenantId(),
                contract.getLandlordId()
        );

        return saved;
    }

    @Transactional
    public Payment processPayment(PaymentDTO dto) {
        Contract contract = contractService.getContractById(dto.getContractId());

        if ("terminated".equals(contract.getContractStatus())) {
            throw new HouseRentalException(400, "合同已终止，无法支付");
        }
        if ("expired".equals(contract.getContractStatus())) {
            throw new HouseRentalException(400, "合同已过期，无法支付");
        }

        String paymentPeriod = dto.getPaymentPeriod() != null ? dto.getPaymentPeriod() : getCurrentPeriod();

        Optional<Payment> existing = paymentRepository.findByContractIdAndPeriod(
                contract.getContractId(), paymentPeriod);

        Payment payment;
        if (existing.isPresent()) {
            payment = existing.get();
            if ("paid".equals(payment.getPaymentStatus())) {
                throw new HouseRentalException(400, "该周期租金已支付");
            }
        } else {
            payment = new Payment();
            payment.setPaymentId(IdGenerator.generatePaymentId());
            payment.setContractId(contract.getContractId());
            payment.setTenantId(contract.getTenantId());
            payment.setPaymentPeriod(paymentPeriod);
        }

        double expectedAmount = contract.getContractRent();
        if (dto.getPaymentAmount() < expectedAmount) {
            throw new HouseRentalException(400, "支付金额不足，应付金额：" + expectedAmount);
        }

        payment.setPaymentAmount(dto.getPaymentAmount());
        payment.setPaymentMethod(dto.getPaymentMethod() != null ? dto.getPaymentMethod() : "wechat");
        payment.setPaymentStatus("paid");
        payment.setPaidAt(LocalDateTime.now());

        Payment saved = paymentRepository.save(payment);

        landlordService.addIncome(contract.getLandlordId(), dto.getPaymentAmount());
        statisticsService.addRentAmount(dto.getPaymentAmount());

        historyService.recordPaymentHistory(
                saved.getPaymentId(),
                "PAID",
                "租金支付成功，金额：" + dto.getPaymentAmount() +
                        "，支付方式：" + (dto.getPaymentMethod() != null ? dto.getPaymentMethod() : "wechat") +
                        "，周期：" + paymentPeriod,
                contract.getHouseId(),
                contract.getTenantId(),
                contract.getLandlordId()
        );

        return saved;
    }

    @Transactional
    public Payment getPaymentById(String paymentId) {
        return paymentRepository.findByPaymentId(paymentId)
                .orElseThrow(() -> new HouseRentalException(404, "支付记录不存在: " + paymentId));
    }

    @Transactional
    public Payment markPaymentAsFailed(String paymentId, String reason) {
        Payment payment = getPaymentById(paymentId);

        if ("paid".equals(payment.getPaymentStatus())) {
            throw new HouseRentalException(400, "支付已成功，无法标记为失败");
        }

        payment.setPaymentStatus("failed");
        Payment saved = paymentRepository.save(payment);

        historyService.recordPaymentHistory(
                saved.getPaymentId(),
                "FAILED",
                "租金支付失败，原因：" + (reason != null ? reason : "未知"),
                null,
                payment.getTenantId(),
                null
        );

        return saved;
    }

    @Transactional
    public Payment refundPayment(String paymentId, String reason) {
        Payment payment = getPaymentById(paymentId);

        if (!"paid".equals(payment.getPaymentStatus())) {
            throw new HouseRentalException(400, "只有已支付的记录才能退款");
        }

        payment.setPaymentStatus("refunded");
        Payment saved = paymentRepository.save(payment);

        historyService.recordPaymentHistory(
                saved.getPaymentId(),
                "REFUND",
                "租金退款，原因：" + (reason != null ? reason : "无"),
                null,
                payment.getTenantId(),
                null
        );

        return saved;
    }

    public double calculateRent(Contract contract) {
        return contract.getContractRent();
    }

    public double calculateRentForPeriod(Contract contract, int months) {
        return contract.getContractRent() * months;
    }

    public List<Payment> getAllPayments() {
        return paymentRepository.findAll();
    }

    public List<Payment> getPaymentsByContract(String contractId) {
        return paymentRepository.findByContractId(contractId);
    }

    public List<Payment> getPaymentsByTenant(String tenantId) {
        return paymentRepository.findByTenantId(tenantId);
    }

    public List<Payment> getPendingPayments() {
        return paymentRepository.findByPaymentStatus("pending");
    }

    public List<Payment> getPaidPayments() {
        return paymentRepository.findByPaymentStatus("paid");
    }

    public List<Payment> getPendingPaymentsByContract(String contractId) {
        return paymentRepository.findByContractIdAndPaymentStatus(contractId, "pending");
    }

    public List<Payment> getPaidPaymentsByContract(String contractId) {
        return paymentRepository.findByContractIdAndPaymentStatus(contractId, "paid");
    }

    public List<Payment> getPendingPaymentsByTenant(String tenantId) {
        return paymentRepository.findByTenantIdAndPaymentStatus(tenantId, "pending");
    }

    public List<Payment> getPaidPaymentsByTenant(String tenantId) {
        return paymentRepository.findByTenantIdAndPaymentStatus(tenantId, "paid");
    }

    public long countTotalPayments() {
        return paymentRepository.countTotalPayments();
    }

    public long countPendingPayments() {
        return paymentRepository.countByStatus("pending");
    }

    public long countPaidPayments() {
        return paymentRepository.countByStatus("paid");
    }

    public long countFailedPayments() {
        return paymentRepository.countByStatus("failed");
    }

    public double getTotalPaidAmount() {
        Double total = paymentRepository.sumTotalPaidAmount();
        return total != null ? total : 0.0;
    }

    public double getTotalAmountByTimeRange(LocalDateTime start, LocalDateTime end) {
        Double total = paymentRepository.sumPaidAmountByTimeRange(start, end);
        return total != null ? total : 0.0;
    }

    @Transactional
    public Payment createPaymentForContract(String contractId, String paymentPeriod, Double amount) {
        Contract contract = contractService.getContractById(contractId);

        Optional<Payment> existing = paymentRepository.findByContractIdAndPeriod(contractId, paymentPeriod);
        if (existing.isPresent()) {
            return existing.get();
        }

        Payment payment = new Payment();
        payment.setPaymentId(IdGenerator.generatePaymentId());
        payment.setContractId(contractId);
        payment.setTenantId(contract.getTenantId());
        payment.setPaymentAmount(amount != null ? amount : contract.getContractRent());
        payment.setPaymentPeriod(paymentPeriod);
        payment.setPaymentStatus("pending");

        Payment saved = paymentRepository.save(payment);

        historyService.recordPaymentHistory(
                saved.getPaymentId(),
                "CREATE",
                "创建租金支付记录，金额：" + saved.getPaymentAmount() + "，周期：" + paymentPeriod,
                contract.getHouseId(),
                contract.getTenantId(),
                contract.getLandlordId()
        );

        return saved;
    }
}
