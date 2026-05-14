package com.houserental.service;

import com.houserental.config.ContractReminderConfig;
import com.houserental.config.ContractReminderConfig.ReminderTypeConfig;
import com.houserental.entity.Contract;
import com.houserental.repository.ContractRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ContractReminderService {

    @Autowired
    private ContractRepository contractRepository;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private ContractReminderConfig reminderConfig;

    private final List<Map<String, Object>> sentReminders = new ArrayList<>();

    public static class ContractType {
        public static final String LONG_TERM = "long_term";
        public static final String SHORT_TERM = "short_term";
        public static final String TEMPORARY = "temporary";
    }

    public String determineContractType(Contract contract) {
        if (contract.getContractStart() == null || contract.getContractEnd() == null) {
            return ContractType.SHORT_TERM;
        }
        long months = ChronoUnit.MONTHS.between(
                contract.getContractStart(),
                contract.getContractEnd()
        );
        return reminderConfig.determineContractType((int) months);
    }

    public int getReminderDaysBefore(Contract contract) {
        String contractType = determineContractType(contract);
        return reminderConfig.getReminderDaysBefore(contractType);
    }

    public int getReminderDaysBefore(String contractType) {
        return reminderConfig.getReminderDaysBefore(contractType);
    }

    public int getReminderFrequency(Contract contract) {
        String contractType = determineContractType(contract);
        return reminderConfig.getReminderFrequency(contractType);
    }

    public int getReminderFrequency(String contractType) {
        return reminderConfig.getReminderFrequency(contractType);
    }

    public ReminderTypeConfig getTypeConfig(String contractType) {
        return reminderConfig.getTypeConfig(contractType);
    }

    public List<String> getAllContractTypes() {
        return reminderConfig.getAllTypes();
    }

    public boolean shouldSendReminder(Contract contract, LocalDate checkDate) {
        if (!"active".equals(contract.getContractStatus())) {
            return false;
        }
        if (contract.getContractEnd() == null) {
            return false;
        }

        int daysBefore = getReminderDaysBefore(contract);
        LocalDate reminderStartDate = contract.getContractEnd().minusDays(daysBefore);

        return !checkDate.isBefore(reminderStartDate) && !checkDate.isAfter(contract.getContractEnd());
    }

    public boolean shouldSendReminder(Contract contract) {
        return shouldSendReminder(contract, LocalDate.now());
    }

    public List<Contract> findContractsNeedingReminder(LocalDate checkDate) {
        List<Contract> activeContracts = contractRepository.findByContractStatus("active");
        List<Contract> needingReminder = new ArrayList<>();

        for (Contract contract : activeContracts) {
            if (shouldSendReminder(contract, checkDate)) {
                needingReminder.add(contract);
            }
        }

        return needingReminder;
    }

    public List<Contract> findContractsNeedingReminder() {
        return findContractsNeedingReminder(LocalDate.now());
    }

    @Async
    public void sendReminderAsync(Contract contract) {
        sendReminder(contract);
    }

    public Map<String, Object> sendReminder(Contract contract) {
        Map<String, Object> reminder = new HashMap<>();
        reminder.put("contractId", contract.getContractId());
        reminder.put("tenantId", contract.getTenantId());
        reminder.put("landlordId", contract.getLandlordId());
        reminder.put("houseId", contract.getHouseId());
        reminder.put("contractEnd", contract.getContractEnd());
        reminder.put("contractType", determineContractType(contract));
        reminder.put("daysBefore", getReminderDaysBefore(contract));
        reminder.put("frequency", getReminderFrequency(contract));
        reminder.put("sentAt", LocalDate.now());
        reminder.put("reminderType", "CONTRACT_EXPIRY");

        sentReminders.add(reminder);

        historyService.recordContractHistory(
                contract.getContractId(),
                "REMINDER_SENT",
                "合同到期提醒已发送，到期日期：" + contract.getContractEnd() +
                        "，剩余天数：" + getReminderDaysBefore(contract),
                contract.getHouseId(),
                contract.getTenantId(),
                contract.getLandlordId()
        );

        return reminder;
    }

    @Scheduled(cron = "0 0 9 * * ?")
    public void checkAndSendReminders() {
        List<Contract> contracts = findContractsNeedingReminder();
        for (Contract contract : contracts) {
            sendReminderAsync(contract);
        }
    }

    public List<Map<String, Object>> getSentReminders() {
        return new ArrayList<>(sentReminders);
    }

    public void clearSentReminders() {
        sentReminders.clear();
    }

    public int countSentRemindersByContractType(String contractType) {
        return (int) sentReminders.stream()
                .filter(r -> contractType.equals(r.get("contractType")))
                .count();
    }

    public long getDaysUntilExpiry(Contract contract) {
        if (contract.getContractEnd() == null) {
            return -1;
        }
        return ChronoUnit.DAYS.between(LocalDate.now(), contract.getContractEnd());
    }
}
