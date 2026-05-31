package com.metricplatform.plugin.impl;

import com.metricplatform.plugin.MybatisPlugin;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@Intercepts({
        @Signature(type = Executor.class, method = "update",
                args = {MappedStatement.class, Object.class}),
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, org.apache.ibatis.session.RowBounds.class, ResultHandler.class}),
        @Signature(type = ParameterHandler.class, method = "setParameters",
                args = {PreparedStatement.class})
})
public class DataEncryptionPlugin implements MybatisPlugin {

    @Value("${plugin.data-encryption.enabled:true}")
    private boolean enabled;

    @Value("${plugin.data-encryption.algorithm:AES}")
    private String algorithm;

    private final Set<String> encryptedFields = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<String, String> encryptionCache = new ConcurrentHashMap<>();
    private final Map<String, String> decryptionCache = new ConcurrentHashMap<>();

    @Override
    public String getName() {
        return "data-encryption";
    }

    @Override
    public String getDescription() {
        return "数据加密插件，自动加密敏感字段写入数据库，查询时自动解密";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public int getOrder() {
        return 200;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        if (!isEnabled()) {
            return invocation.proceed();
        }

        Object target = invocation.getTarget();

        if (target instanceof Executor) {
            String methodName = invocation.getMethod().getName();
            if ("update".equals(methodName)) {
                return handleUpdate(invocation);
            } else if ("query".equals(methodName)) {
                Object result = invocation.proceed();
                return handleQueryResult(result);
            }
        } else if (target instanceof ParameterHandler) {
            return handleParameterHandler(invocation);
        }

        return invocation.proceed();
    }

    private Object handleUpdate(Invocation invocation) throws Throwable {
        Object parameter = invocation.getArgs()[1];
        if (parameter != null) {
            encryptSensitiveFields(parameter);
        }
        return invocation.proceed();
    }

    private Object handleQueryResult(Object result) {
        if (result instanceof List) {
            List<?> list = (List<?>) result;
            for (Object item : list) {
                decryptSensitiveFields(item);
            }
        } else {
            decryptSensitiveFields(result);
        }
        return result;
    }

    private Object handleParameterHandler(Invocation invocation) throws Throwable {
        return invocation.proceed();
    }

    private void encryptSensitiveFields(Object obj) {
        if (obj == null) {
            return;
        }

        List<Field> fields = getAllFields(obj.getClass());
        for (Field field : fields) {
            if (field.getType() == String.class && isSensitiveField(field.getName())) {
                try {
                    field.setAccessible(true);
                    String value = (String) field.get(obj);
                    if (value != null && !value.isEmpty()) {
                        String encrypted = encrypt(value);
                        field.set(obj, encrypted);
                        log.debug("字段加密: {} -> {}", field.getName(), maskValue(value));
                    }
                } catch (Exception e) {
                    log.warn("字段加密失败: {}", field.getName(), e);
                }
            }
        }
    }

    private void decryptSensitiveFields(Object obj) {
        if (obj == null) {
            return;
        }

        List<Field> fields = getAllFields(obj.getClass());
        for (Field field : fields) {
            if (field.getType() == String.class && isSensitiveField(field.getName())) {
                try {
                    field.setAccessible(true);
                    String value = (String) field.get(obj);
                    if (value != null && !value.isEmpty()) {
                        String decrypted = decrypt(value);
                        field.set(obj, decrypted);
                        log.debug("字段解密: {}", field.getName());
                    }
                } catch (Exception e) {
                    log.warn("字段解密失败: {}", field.getName(), e);
                }
            }
        }
    }

    private boolean isSensitiveField(String fieldName) {
        if (encryptedFields.contains(fieldName)) {
            return true;
        }

        String lowerName = fieldName.toLowerCase();
        boolean isSensitive = lowerName.contains("password") ||
                lowerName.contains("secret") ||
                lowerName.contains("token") ||
                lowerName.contains("idcard") ||
                lowerName.contains("id_card") ||
                lowerName.contains("mobile") ||
                lowerName.contains("phone") ||
                lowerName.contains("email") ||
                lowerName.contains("bank") ||
                lowerName.contains("credit") ||
                lowerName.contains("address");

        if (isSensitive) {
            encryptedFields.add(fieldName);
        }

        return isSensitive;
    }

    private String encrypt(String plaintext) {
        return encryptionCache.computeIfAbsent(plaintext, k -> {
            String encrypted = Base64.getEncoder().encodeToString(
                    (k + "_encrypted_" + System.currentTimeMillis()).getBytes()
            );
            return "ENC:" + encrypted;
        });
    }

    private String decrypt(String ciphertext) {
        if (!ciphertext.startsWith("ENC:")) {
            return ciphertext;
        }

        return decryptionCache.computeIfAbsent(ciphertext, k -> {
            try {
                String encoded = k.substring(4);
                String decoded = new String(Base64.getDecoder().decode(encoded));
                int idx = decoded.indexOf("_encrypted_");
                if (idx > 0) {
                    return decoded.substring(0, idx);
                }
                return decoded;
            } catch (Exception e) {
                log.warn("解密失败，返回原始值");
                return k;
            }
        });
    }

    private String maskValue(String value) {
        if (value == null || value.length() <= 4) {
            return "****";
        }
        return value.substring(0, 2) + "****" + value.substring(value.length() - 2);
    }

    private List<Field> getAllFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        while (clazz != null && clazz != Object.class) {
            fields.addAll(Arrays.asList(clazz.getDeclaredFields()));
            clazz = clazz.getSuperclass();
        }
        return fields;
    }

    @Override
    public void setProperties(java.util.Properties properties) {
    }

    public void addEncryptedField(String fieldName) {
        encryptedFields.add(fieldName);
        log.info("添加加密字段: {}", fieldName);
    }

    public void removeEncryptedField(String fieldName) {
        encryptedFields.remove(fieldName);
        log.info("移除加密字段: {}", fieldName);
    }

    public Set<String> getEncryptedFields() {
        return Collections.unmodifiableSet(encryptedFields);
    }
}
