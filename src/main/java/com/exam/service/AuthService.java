package com.exam.service;

import com.exam.dto.LoginDTO;
import com.exam.vo.LoginVO;

public interface AuthService {
    LoginVO login(LoginDTO loginDTO);
    void logout(String token);
    LoginVO getCurrentUserInfo(Long userId);
}
