package com.metricplatform.plugin.impl;

import com.metricplatform.plugin.MybatisPlugin;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@Intercepts({
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, org.apache.ibatis.session.RowBounds.class, ResultHandler.class})
})
public class DataDesensitizationPlugin implements MybatisPlugin {

    @Value("${plugin.data-desensitization.enabled:true}")
    private boolean enabled;

    private final Map<String, Desensitizer> desensitizers = new ConcurrentHashMap<>();

    public interface Desensitizer {
        String desensitize(String value);
    }

    public DataDesensitizationPlugin() {
        registerDefaultDesensitizers();
    }

    private void registerDefaultDesensitizers() {
        desensitizers.put("mobile", this::desensitizeMobile);
        desensitizers.put("phone", this::desensitizeMobile);
        desensitizers.put("email", this::desensitizeEmail);
        desensitizers.put("idcard", this::desensitizeIdCard);
        desensitizers.put("id_card", this::desensitizeIdCard);
        desensitizers.put("bank", this::desensitizeBankCard);
        desensitizers.put("credit", this::desensitizeBankCard);
        desensitizers.put("name", this::desensitizeName);
        desensitizers.put("username", this::desensitizeName);
        desensitizers.put("address", this::desensitizeAddress);
        desensitizers.put("password", v -> "********");
        desensitizers.put("secret", v -> "********");
        desensitizers.put("token", v -> "********");
    }

    @Override
    public String getName() {
        return "data-desensitization";
    }

    @Override
    public String getDescription() {
        return "数据脱敏插件，查询结果自动脱敏敏感字段，保护用户隐私";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public int getOrder() {
        return 300;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        if (!isEnabled()) {
            return invocation.proceed();
        }

        Object result = invocation.proceed();
        return desensitizeResult(result);
    }

    private Object desensitizeResult(Object result) {
        if (result instanceof List) {
            List<?> list = (List<?>) result;
            for (Object item : list) {
                desensitizeObject(item);
            }
        } else {
            desensitizeObject(result);
        }
        return result;
    }

    private void desensitizeObject(Object obj) {
        if (obj == null) {
            return;
        }

        List<Field> fields = getAllFields(obj.getClass());
        for (Field field : fields) {
            if (field.getType() == String.class) {
                Desensitizer desensitizer = findDesensitizer(field.getName());
                if (desensitizer != null) {
                    try {
                        field.setAccessible(true);
                        String value = (String) field.get(obj);
                        if (value != null && !value.isEmpty()) {
                            String desensitized = desensitizer.desensitize(value);
                            field.set(obj, desensitized);
                            log.debug("字段脱敏: {} -> {}", field.getName(), desensitized);
                        }
                    } catch (Exception e) {
                        log.warn("字段脱敏失败: {}", field.getName(), e);
                    }
                }
            }
        }
    }

    private Desensitizer findDesensitizer(String fieldName) {
        String lowerName = fieldName.toLowerCase();
        for (Map.Entry<String, Desensitizer> entry : desensitizers.entrySet()) {
            if (lowerName.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String desensitizeMobile(String mobile) {
        if (mobile == null || mobile.length() < 7) {
            return mobile;
        }
        return mobile.substring(0, 3) + "****" + mobile.substring(mobile.length() - 4);
    }

    private String desensitizeEmail(String email) {
        if (email == null || !email.contains("@")) {
            return email;
        }
        int atIndex = email.indexOf("@");
        String username = email.substring(0, atIndex);
        String domain = email.substring(atIndex);
        if (username.length() <= 2) {
            return "*" + domain;
        }
        return username.charAt(0) + "****" + username.charAt(username.length() - 1) + domain;
    }

    private String desensitizeIdCard(String idCard) {
        if (idCard == null || idCard.length() < 10) {
            return idCard;
        }
        return idCard.substring(0, 6) + "********" + idCard.substring(idCard.length() - 4);
    }

    private String desensitizeBankCard(String bankCard) {
        if (bankCard == null || bankCard.length() < 8) {
            return bankCard;
        }
        return bankCard.substring(0, 4) + " **** **** " + bankCard.substring(bankCard.length() - 4);
    }

    private String desensitizeName(String name) {
        if (name == null || name.length() <= 1) {
            return name;
        }
        return name.charAt(0) + "*".repeat(Math.max(0, name.length() - 1));
    }

    private String desensitizeAddress(String address) {
        if (address == null || address.length() < 6) {
            return address;
        }
        return address.substring(0, 6) + "****";
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

    public void registerDesensitizer(String fieldType, Desensitizer desensitizer) {
        desensitizers.put(fieldType, desensitizer);
        log.info("注册脱敏器: {}", fieldType);
    }

    public void unregisterDesensitizer(String fieldType) {
        desensitizers.remove(fieldType);
        log.info("移除脱敏器: {}", fieldType);
    }
}
