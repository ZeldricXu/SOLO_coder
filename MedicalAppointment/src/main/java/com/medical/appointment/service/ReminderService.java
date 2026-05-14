package com.medical.appointment.service;

import com.medical.appointment.entity.Appointment;
import com.medical.appointment.entity.Patient;
import com.medical.appointment.repository.AppointmentRepository;
import com.medical.appointment.repository.PatientRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ReminderService {

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);

    private static final long HOURS_BEFORE_HIGH_FREQUENCY = 24;
    private static final long HOURS_BEFORE_LOW_FREQUENCY = 72;
    
    private static final int HIGH_FREQUENCY_INTERVAL_HOURS = 2;
    private static final int LOW_FREQUENCY_INTERVAL_HOURS = 24;

    private final AppointmentRepository appointmentRepository;
    private final PatientRepository patientRepository;
    
    private final Map<String, List<ReminderRecord>> sentReminders = new ConcurrentHashMap<>();

    public static class ReminderRecord {
        private final LocalDateTime sentAt;
        private final String reminderType;
        private final String channel;

        public ReminderRecord(LocalDateTime sentAt, String reminderType, String channel) {
            this.sentAt = sentAt;
            this.reminderType = reminderType;
            this.channel = channel;
        }

        public LocalDateTime getSentAt() {
            return sentAt;
        }

        public String getReminderType() {
            return reminderType;
        }

        public String getChannel() {
            return channel;
        }
    }

    public static class ReminderResult {
        private final boolean sent;
        private final String appointmentId;
        private final String patientName;
        private final String phone;
        private final String message;
        private final String reminderType;
        private final LocalDateTime scheduledTime;

        public ReminderResult(boolean sent, String appointmentId, String patientName, 
                             String phone, String message, String reminderType, 
                             LocalDateTime scheduledTime) {
            this.sent = sent;
            this.appointmentId = appointmentId;
            this.patientName = patientName;
            this.phone = phone;
            this.message = message;
            this.reminderType = reminderType;
            this.scheduledTime = scheduledTime;
        }

        public boolean isSent() {
            return sent;
        }

        public String getAppointmentId() {
            return appointmentId;
        }

        public String getPatientName() {
            return patientName;
        }

        public String getPhone() {
            return phone;
        }

        public String getMessage() {
            return message;
        }

        public String getReminderType() {
            return reminderType;
        }

        public LocalDateTime getScheduledTime() {
            return scheduledTime;
        }
    }

    public ReminderService(AppointmentRepository appointmentRepository, PatientRepository patientRepository) {
        this.appointmentRepository = appointmentRepository;
        this.patientRepository = patientRepository;
    }

    public ReminderResult sendReminder(String appointmentId) {
        Appointment appointment = appointmentRepository.findById(appointmentId).orElse(null);
        if (appointment == null) {
            log.warn("发送提醒失败 - 挂号不存在: {}", appointmentId);
            return new ReminderResult(false, appointmentId, null, null, 
                    "挂号不存在", "ERROR", null);
        }

        Patient patient = patientRepository.findById(appointment.getPatientId()).orElse(null);
        if (patient == null) {
            log.warn("发送提醒失败 - 患者不存在: {}", appointment.getPatientId());
            return new ReminderResult(false, appointmentId, null, null, 
                    "患者不存在", "ERROR", null);
        }

        String reminderType = determineReminderType(appointment.getAppointmentTime());
        String message = buildReminderMessage(appointment, patient, reminderType);
        String channel = "SMS";

        boolean canSend = shouldSendReminder(appointmentId, reminderType);
        if (!canSend) {
            log.info("提醒发送频率限制 - 挂号ID: {}, 类型: {}", appointmentId, reminderType);
            return new ReminderResult(false, appointmentId, patient.getPatientName(), 
                    patient.getPatientPhone(), message, reminderType, appointment.getAppointmentTime());
        }

        log.info("发送就诊提醒 - 患者: {}, 电话: {}, 类型: {}, 消息: {}", 
                patient.getPatientName(), patient.getPatientPhone(), reminderType, message);
        
        recordReminderSent(appointmentId, reminderType, channel);
        
        return new ReminderResult(true, appointmentId, patient.getPatientName(), 
                patient.getPatientPhone(), message, reminderType, appointment.getAppointmentTime());
    }

    public List<ReminderResult> checkAndSendReminders(LocalDateTime now) {
        List<ReminderResult> results = new ArrayList<>();
        List<Appointment> appointments = appointmentRepository.findByAppointmentStatus("appointed");
        
        for (Appointment appointment : appointments) {
            if (appointment.getAppointmentTime() != null) {
                Duration timeUntil = Duration.between(now, appointment.getAppointmentTime());
                if (timeUntil.toHours() <= HOURS_BEFORE_LOW_FREQUENCY && timeUntil.toHours() > 0) {
                    ReminderResult result = sendReminder(appointment.getAppointmentId());
                    if (result.isSent()) {
                        results.add(result);
                    }
                }
            }
        }
        return results;
    }

    private String determineReminderType(LocalDateTime appointmentTime) {
        if (appointmentTime == null) {
            return "GENERAL";
        }
        
        Duration timeUntil = Duration.between(LocalDateTime.now(), appointmentTime);
        long hoursUntil = timeUntil.toHours();
        
        if (hoursUntil <= HOURS_BEFORE_HIGH_FREQUENCY && hoursUntil > 0) {
            return "HIGH_FREQUENCY";
        } else if (hoursUntil <= HOURS_BEFORE_LOW_FREQUENCY) {
            return "LOW_FREQUENCY";
        }
        return "GENERAL";
    }

    private String buildReminderMessage(Appointment appointment, Patient patient, String reminderType) {
        String patientName = patient.getPatientName();
        LocalDateTime time = appointment.getAppointmentTime();
        String timeStr = time != null ? time.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) : "指定时间";
        
        switch (reminderType) {
            case "HIGH_FREQUENCY":
                return String.format("【就诊提醒】尊敬的%s，您预约于%s的就诊将在24小时内开始，请准时前往。", patientName, timeStr);
            case "LOW_FREQUENCY":
                return String.format("【就诊提醒】尊敬的%s，您预约于%s的就诊，请提前做好准备。", patientName, timeStr);
            default:
                return String.format("【就诊提醒】尊敬的%s，您的预约就诊时间为%s。", patientName, timeStr);
        }
    }

    private boolean shouldSendReminder(String appointmentId, String reminderType) {
        List<ReminderRecord> records = sentReminders.getOrDefault(appointmentId, new ArrayList<>());
        
        int intervalHours = "HIGH_FREQUENCY".equals(reminderType) ? 
                HIGH_FREQUENCY_INTERVAL_HOURS : LOW_FREQUENCY_INTERVAL_HOURS;
        
        return records.stream()
                .filter(r -> r.getReminderType().equals(reminderType))
                .noneMatch(r -> Duration.between(r.getSentAt(), LocalDateTime.now()).toHours() < intervalHours);
    }

    private void recordReminderSent(String appointmentId, String reminderType, String channel) {
        sentReminders.computeIfAbsent(appointmentId, k -> new ArrayList<>())
                .add(new ReminderRecord(LocalDateTime.now(), reminderType, channel));
    }

    public List<ReminderRecord> getSentReminders(String appointmentId) {
        return new ArrayList<>(sentReminders.getOrDefault(appointmentId, new ArrayList<>()));
    }

    public int getReminderCount(String appointmentId) {
        return sentReminders.getOrDefault(appointmentId, new ArrayList<>()).size();
    }

    public void clearReminderHistory(String appointmentId) {
        sentReminders.remove(appointmentId);
    }

    public void clearAllReminderHistory() {
        sentReminders.clear();
    }

    public int getReminderIntervalHours(String reminderType) {
        if ("HIGH_FREQUENCY".equals(reminderType)) {
            return HIGH_FREQUENCY_INTERVAL_HOURS;
        }
        return LOW_FREQUENCY_INTERVAL_HOURS;
    }

    public long getHighFrequencyThresholdHours() {
        return HOURS_BEFORE_HIGH_FREQUENCY;
    }

    public long getLowFrequencyThresholdHours() {
        return HOURS_BEFORE_LOW_FREQUENCY;
    }
}
