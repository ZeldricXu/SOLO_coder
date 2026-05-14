package com.authcenter.service;

import com.authcenter.entity.MfaRecord;
import com.authcenter.entity.User;
import com.authcenter.exception.AuthException;
import com.authcenter.repository.MfaRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

@Service
public class MfaService {
    
    private static final Logger logger = LoggerFactory.getLogger(MfaService.class);
    
    @Autowired
    private MfaRecordRepository mfaRecordRepository;
    
    @Autowired
    private AuditService auditService;
    
    @Autowired
    private MfaTaskQueueService taskQueueService;
    
    @Value("${mfa.sms.expiration:300000}")
    private long smsExpiration;
    
    @Value("${mfa.sms.code-length:6}")
    private int smsCodeLength;
    
    @Value("${mfa.email.expiration:300000}")
    private long emailExpiration;
    
    @Value("${mfa.use-queue:true}")
    private boolean useQueue;
    
    private final Random random = new Random();
    
    @Transactional
    public MfaRecord generateAndSendCode(User user) {
        String mfaType = user.getMfaType() != null ? user.getMfaType() : "sms";
        String code = generateCode(mfaType);
        long expiration = "sms".equals(mfaType) ? smsExpiration : emailExpiration;
        
        MfaRecord mfaRecord = new MfaRecord();
        mfaRecord.setMfaId(UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        mfaRecord.setUserId(user.getUserId());
        mfaRecord.setMfaType(mfaType);
        mfaRecord.setMfaCode(code);
        mfaRecord.setCreatedAt(LocalDateTime.now());
        mfaRecord.setExpiresAt(LocalDateTime.now().plusNanos(expiration * 1000000));
        mfaRecord.setVerified(false);
        
        MfaRecord savedRecord = mfaRecordRepository.save(mfaRecord);
        
        String target = getTarget(user, mfaType);
        
        if (useQueue) {
            MfaTaskQueueService.MfaTask task = taskQueueService.submitTask(
                    user.getUserId(),
                    mfaType,
                    code,
                    target,
                    null,
                    null
            );
            logger.info("MFA code queued for delivery: taskId={}, userId={}, type={}", 
                    task.getTaskId(), user.getUserId(), mfaType);
        } else {
            sendCodeSync(user, mfaType, code);
        }
        
        logger.info("Generated MFA record: mfaId={}, userId={}, type={}", 
                savedRecord.getMfaId(), user.getUserId(), mfaType);
        
        return savedRecord;
    }
    
    @Transactional
    public MfaRecord generateAndSendCodeWithContext(User user, String userAgent, String ipAddress) {
        String mfaType = user.getMfaType() != null ? user.getMfaType() : "sms";
        String code = generateCode(mfaType);
        long expiration = "sms".equals(mfaType) ? smsExpiration : emailExpiration;
        
        MfaRecord mfaRecord = new MfaRecord();
        mfaRecord.setMfaId(UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        mfaRecord.setUserId(user.getUserId());
        mfaRecord.setMfaType(mfaType);
        mfaRecord.setMfaCode(code);
        mfaRecord.setCreatedAt(LocalDateTime.now());
        mfaRecord.setExpiresAt(LocalDateTime.now().plusNanos(expiration * 1000000));
        mfaRecord.setVerified(false);
        
        MfaRecord savedRecord = mfaRecordRepository.save(mfaRecord);
        
        String target = getTarget(user, mfaType);
        
        if (useQueue) {
            MfaTaskQueueService.MfaTask task = taskQueueService.submitTask(
                    user.getUserId(),
                    mfaType,
                    code,
                    target,
                    userAgent,
                    ipAddress
            );
            logger.info("MFA code queued for delivery: taskId={}, userId={}, type={}", 
                    task.getTaskId(), user.getUserId(), mfaType);
        } else {
            sendCodeSync(user, mfaType, code);
        }
        
        return savedRecord;
    }
    
    @Transactional
    public boolean verifyCode(String userId, String mfaType, String code) {
        Optional<MfaRecord> recordOpt = mfaRecordRepository
                .findByUserIdAndMfaTypeAndVerifiedOrderByCreatedAtDesc(userId, mfaType, false);
        
        MfaRecord record = recordOpt.orElseThrow(() -> 
                new AuthException(400, "没有找到有效的验证码记录"));
        
        if (record.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new AuthException(400, "验证码已过期");
        }
        
        if (!record.getMfaCode().equals(code)) {
            auditService.log(userId, "mfa_verify", "failure", null, null, "验证码错误");
            throw new AuthException(400, "验证码错误");
        }
        
        record.setVerified(true);
        mfaRecordRepository.save(record);
        
        auditService.log(userId, "mfa_verify", "success", null, null, "多因素认证成功");
        logger.info("MFA code verified: userId={}, type={}", userId, mfaType);
        return true;
    }
    
    public MfaRecord getLatestMfaRecord(String userId, String mfaType) {
        return mfaRecordRepository
                .findByUserIdAndMfaTypeAndVerifiedOrderByCreatedAtDesc(userId, mfaType, false)
                .orElse(null);
    }
    
    public boolean hasActiveMfaRecord(String userId, String mfaType) {
        MfaRecord record = getLatestMfaRecord(userId, mfaType);
        return record != null && record.getExpiresAt().isAfter(LocalDateTime.now());
    }
    
    public void invalidateActiveMfaRecords(String userId, String mfaType) {
        Optional<MfaRecord> recordOpt = mfaRecordRepository
                .findByUserIdAndMfaTypeAndVerifiedOrderByCreatedAtDesc(userId, mfaType, false);
        
        recordOpt.ifPresent(record -> {
            record.setExpiresAt(LocalDateTime.now().minusMinutes(1));
            mfaRecordRepository.save(record);
        });
    }
    
    public MfaTaskQueueService.MfaTask submitManualSendTask(User user, String mfaType, String code) {
        String target = getTarget(user, mfaType);
        return taskQueueService.submitTask(user.getUserId(), mfaType, code, target);
    }
    
    public long getPendingTaskCount() {
        return taskQueueService.getPendingTaskCount();
    }
    
    public long getProcessingTaskCount() {
        return taskQueueService.getProcessingTaskCount();
    }
    
    public long getDeadLetterCount() {
        return taskQueueService.getDeadLetterCount();
    }
    
    private String generateCode(String mfaType) {
        if ("sms".equals(mfaType)) {
            StringBuilder code = new StringBuilder();
            for (int i = 0; i < smsCodeLength; i++) {
                code.append(random.nextInt(10));
            }
            return code.toString();
        } else {
            StringBuilder code = new StringBuilder();
            String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
            for (int i = 0; i < 8; i++) {
                code.append(chars.charAt(random.nextInt(chars.length())));
            }
            return code.toString();
        }
    }
    
    private String getTarget(User user, String mfaType) {
        if ("sms".equals(mfaType)) {
            return user.getPhone();
        } else if ("email".equals(mfaType)) {
            return user.getEmail();
        }
        return user.getEmail();
    }
    
    private void sendCodeSync(User user, String mfaType, String code) {
        if ("sms".equals(mfaType) && user.getPhone() != null) {
            sendSms(user.getPhone(), code);
        } else if ("email".equals(mfaType) && user.getEmail() != null) {
            sendEmail(user.getEmail(), code);
        }
    }
    
    private void sendSms(String phone, String code) {
        logger.info("[MFA][SMS][SYNC] 发送短信验证码到 {}: {}", maskPhone(phone), code);
        System.out.println("[MOCK] [SMS] 发送短信验证码到 " + maskPhone(phone) + ": " + code);
    }
    
    private void sendEmail(String email, String code) {
        logger.info("[MFA][EMAIL][SYNC] 发送邮箱验证码到 {}: {}", maskEmail(email), code);
        System.out.println("[MOCK] [EMAIL] 发送邮箱验证码到 " + maskEmail(email) + ": " + code);
    }
    
    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) return "***";
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
    
    private String maskEmail(String email) {
        if (email == null) return "***";
        int atIndex = email.indexOf('@');
        if (atIndex <= 1) return "***" + email.substring(atIndex);
        return email.substring(0, 2) + "***" + email.substring(atIndex);
    }
    
    public void setUseQueue(boolean useQueue) {
        this.useQueue = useQueue;
    }
    
    public boolean isUseQueue() {
        return useQueue;
    }
}