package com.exam.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.exam.common.BusinessException;
import com.exam.common.Constants;
import com.exam.common.ResultCode;
import com.exam.dto.LoginDTO;
import com.exam.entity.Role;
import com.exam.entity.User;
import com.exam.mapper.RoleMapper;
import com.exam.mapper.UserMapper;
import com.exam.service.AuthService;
import com.exam.util.JwtUtil;
import com.exam.vo.LoginVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final RedisTemplate<String, Object> redisTemplate;

    @Override
    public LoginVO login(LoginDTO loginDTO) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, loginDTO.getUsername()));

        if (user == null) {
            throw new BusinessException(ResultCode.LOGIN_ERROR);
        }

        if (!passwordEncoder.matches(loginDTO.getPassword(), user.getPassword())) {
            throw new BusinessException(ResultCode.LOGIN_ERROR);
        }

        if (user.getStatus() == 0) {
            throw new BusinessException(ResultCode.USER_DISABLED);
        }

        List<Role> roles = roleMapper.selectRolesByUserId(user.getId());
        List<String> roleCodes = roles.stream().map(Role::getRoleCode).collect(Collectors.toList());

        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), roleCodes);

        redisTemplate.opsForValue().set(
                Constants.REDIS_TOKEN_PREFIX + user.getId(),
                token,
                24,
                TimeUnit.HOURS
        );

        user.setLastLoginTime(LocalDateTime.now());
        userMapper.updateById(user);

        log.info("用户登录成功: {}", user.getUsername());

        return new LoginVO(token, user.getId(), user.getUsername(),
                user.getRealName(), user.getAvatar(), roleCodes, user.getSubjectId());
    }

    @Override
    public void logout(String token) {
        try {
            Long userId = jwtUtil.getUserIdFromToken(token);
            redisTemplate.delete(Constants.REDIS_TOKEN_PREFIX + userId);
        } catch (Exception e) {
            log.warn("登出时解析token失败: {}", e.getMessage());
        }
    }

    @Override
    public LoginVO getCurrentUserInfo(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND);
        }

        List<Role> roles = roleMapper.selectRolesByUserId(userId);
        List<String> roleCodes = roles.stream().map(Role::getRoleCode).collect(Collectors.toList());

        return new LoginVO(null, user.getId(), user.getUsername(),
                user.getRealName(), user.getAvatar(), roleCodes, user.getSubjectId());
    }
}
