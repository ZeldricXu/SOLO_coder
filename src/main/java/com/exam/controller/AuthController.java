package com.exam.controller;

import com.exam.common.Result;
import com.exam.dto.LoginDTO;
import com.exam.service.AuthService;
import com.exam.vo.LoginVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Value("${jwt.header}")
    private String header;

    @Value("${jwt.prefix}")
    private String prefix;

    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO loginDTO) {
        return Result.success(authService.login(loginDTO));
    }

    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request) {
        String token = request.getHeader(header);
        if (token != null && token.startsWith(prefix)) {
            token = token.substring(prefix.length()).trim();
        }
        authService.logout(token);
        return Result.success();
    }

    @GetMapping("/userinfo")
    public Result<LoginVO> getUserInfo(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("currentUserId");
        return Result.success(authService.getCurrentUserInfo(userId));
    }
}
