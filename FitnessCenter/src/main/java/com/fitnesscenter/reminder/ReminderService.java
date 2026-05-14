package com.fitnesscenter.reminder;

import com.fitnesscenter.config.ReminderConfig;
import com.fitnesscenter.model.Member;
import com.fitnesscenter.repository.MemberRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class ReminderService {

    private final MemberRepository memberRepository;
    private final ReminderConfig reminderConfig;
    private final Map<String, AtomicInteger> memberReminderCounts = new ConcurrentHashMap<>();
    private final AtomicInteger totalRemindersSent = new AtomicInteger(0);
    private final AtomicInteger activeMemberReminders = new AtomicInteger(0);
    private final AtomicInteger inactiveMemberReminders = new AtomicInteger(0);

    public ReminderService(MemberRepository memberRepository, ReminderConfig reminderConfig) {
        this.memberRepository = memberRepository;
        this.reminderConfig = reminderConfig;
    }

    public List<ReminderResult> sendTrainingReminders() {
        List<ReminderResult> results = new ArrayList<>();
        List<Member> members = memberRepository.findByMemberStatus("active");

        for (Member member : members) {
            ReminderResult result = shouldSendReminder(member);
            if (result.shouldSend) {
                sendReminder(member, result.frequencyType);
                results.add(result);
            }
        }

        return results;
    }

    public ReminderResult shouldSendReminder(Member member) {
        int trainingCount = member.getTrainingCount() != null ? member.getTrainingCount() : 0;
        ReminderConfig.FrequencyRule rule = reminderConfig.getRuleByTrainingCount(trainingCount);

        String frequencyType = rule.getName();
        int frequencyDays = rule.getFrequencyDays();

        memberReminderCounts.computeIfAbsent(member.getMemberId(), k -> new AtomicInteger(0));

        if (isActiveFrequencyType(frequencyType)) {
            activeMemberReminders.incrementAndGet();
        } else {
            inactiveMemberReminders.incrementAndGet();
        }

        return new ReminderResult(
                member.getMemberId(),
                true,
                frequencyType,
                frequencyDays,
                trainingCount,
                LocalDate.now().toString()
        );
    }

    private boolean isActiveFrequencyType(String frequencyType) {
        return "active".equals(frequencyType) || "super-active".equals(frequencyType);
    }

    public boolean sendReminder(Member member, String frequencyType) {
        try {
            memberReminderCounts.computeIfAbsent(member.getMemberId(), k -> new AtomicInteger(0)).incrementAndGet();
            totalRemindersSent.incrementAndGet();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public List<String> collectTrainingRecordsToRemind(String memberId) {
        List<String> records = new ArrayList<>();
        Member member = memberRepository.findByMemberId(memberId).orElse(null);
        if (member == null) {
            return records;
        }

        if (member.getTrainingCount() != null && member.getTrainingCount() > 0) {
            records.add("Last training count: " + member.getTrainingCount());
        }
        if (member.getTotalCalories() != null && member.getTotalCalories() > 0) {
            records.add("Total calories burned: " + member.getTotalCalories());
        }
        if (member.getBookingCount() != null && member.getBookingCount() > 0) {
            records.add("Booking count: " + member.getBookingCount());
        }

        return records;
    }

    public int getReminderCount(String memberId) {
        AtomicInteger count = memberReminderCounts.get(memberId);
        return count != null ? count.get() : 0;
    }

    public int getTotalRemindersSent() {
        return totalRemindersSent.get();
    }

    public int getActiveMemberReminders() {
        return activeMemberReminders.get();
    }

    public int getInactiveMemberReminders() {
        return inactiveMemberReminders.get();
    }

    public void resetReminderStats() {
        memberReminderCounts.clear();
        totalRemindersSent.set(0);
        activeMemberReminders.set(0);
        inactiveMemberReminders.set(0);
    }

    public ReminderConfig getReminderConfig() {
        return reminderConfig;
    }

    public int getActiveThreshold() {
        return reminderConfig.getActiveThreshold();
    }

    public int getFrequencyDaysByTrainingCount(int trainingCount) {
        return reminderConfig.getFrequencyDays(trainingCount);
    }

    public String getFrequencyTypeByTrainingCount(int trainingCount) {
        return reminderConfig.getFrequencyType(trainingCount);
    }

    public static class ReminderResult {
        public final String memberId;
        public final boolean shouldSend;
        public final String frequencyType;
        public final int frequencyDays;
        public final int trainingCount;
        public final String reminderDate;

        public ReminderResult(String memberId, boolean shouldSend, String frequencyType,
                              int frequencyDays, int trainingCount, String reminderDate) {
            this.memberId = memberId;
            this.shouldSend = shouldSend;
            this.frequencyType = frequencyType;
            this.frequencyDays = frequencyDays;
            this.trainingCount = trainingCount;
            this.reminderDate = reminderDate;
        }
    }
}
