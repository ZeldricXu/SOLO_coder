package com.paygateway.controller;

import com.paygateway.service.CallbackService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/callback")
@RequiredArgsConstructor
public class CallbackController {
    
    private final CallbackService callbackService;
    
    @PostMapping("/alipay")
    public String alipayCallback(HttpServletRequest request) {
        log.info("收到支付宝回调请求");
        
        Map<String, String> params = new HashMap<>();
        Map<String, String[]> parameterMap = request.getParameterMap();
        for (String name : parameterMap.keySet()) {
            String[] values = parameterMap.get(name);
            StringBuilder valueStr = new StringBuilder();
            for (int i = 0; i < values.length; i++) {
                valueStr.append(i == 0 ? "" : ",").append(values[i]);
            }
            params.put(name, valueStr.toString());
        }
        
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (body.length() > 0) {
                body.append("&");
            }
            body.append(entry.getKey()).append("=").append(entry.getValue());
        }
        
        String result = callbackService.handleCallback("alipay", body.toString(), new HashMap<>());
        
        log.info("支付宝回调处理结果：{}", result);
        return result;
    }
    
    @PostMapping("/wechat")
    public String wechatCallback(HttpServletRequest request) {
        log.info("收到微信支付回调请求");
        
        String body = readRequestBody(request);
        
        Map<String, String> headers = new HashMap<>();
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            headers.put(headerName.toLowerCase(), request.getHeader(headerName));
        }
        
        String result = callbackService.handleCallback("wechat", body, headers);
        
        log.info("微信支付回调处理结果：{}", result);
        return result;
    }
    
    private String readRequestBody(HttpServletRequest request) {
        StringBuilder stringBuilder = new StringBuilder();
        BufferedReader bufferedReader = null;
        try {
            bufferedReader = request.getReader();
            char[] charBuffer = new char[128];
            int bytesRead = -1;
            while ((bytesRead = bufferedReader.read(charBuffer)) > 0) {
                stringBuilder.append(charBuffer, 0, bytesRead);
            }
        } catch (IOException e) {
            log.error("读取请求体失败", e);
        } finally {
            if (bufferedReader != null) {
                try {
                    bufferedReader.close();
                } catch (IOException e) {
                    log.error("关闭BufferedReader失败", e);
                }
            }
        }
        return stringBuilder.toString();
    }
}
