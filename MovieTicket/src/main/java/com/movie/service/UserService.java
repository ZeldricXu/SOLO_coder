package com.movie.service;

import com.movie.dto.UserCreateRequest;
import com.movie.entity.User;
import com.movie.exception.MovieException;
import com.movie.repository.UserRepository;
import com.movie.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class UserService {

    @Autowired
    private UserRepository userRepository;

    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    public Optional<User> getUserById(String userId) {
        return userRepository.findByUserId(userId);
    }

    public User getUserOrThrow(String userId) {
        return userRepository.findByUserId(userId)
                .orElseThrow(() -> new MovieException(404, "用户不存在: " + userId));
    }

    public Optional<User> getUserByPhone(String phone) {
        return userRepository.findByUserPhone(phone);
    }

    @Transactional
    public User createUser(UserCreateRequest request) {
        if (request.getUserPhone() != null && userRepository.existsByUserPhone(request.getUserPhone())) {
            throw new MovieException(400, "手机号已注册: " + request.getUserPhone());
        }
        User user = new User();
        user.setUserId(IdGenerator.generateUserId());
        user.setUserName(request.getUserName());
        user.setUserPhone(request.getUserPhone());
        user.setUserStatus("active");
        user.setRegisteredAt(LocalDateTime.now());
        return userRepository.save(user);
    }

    @Transactional
    public User getOrCreateUser(String userId, String userName, String phone) {
        if (userId != null) {
            Optional<User> existing = userRepository.findByUserId(userId);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        if (phone != null) {
            Optional<User> existing = userRepository.findByUserPhone(phone);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        User user = new User();
        user.setUserId(IdGenerator.generateUserId());
        user.setUserName(userName != null ? userName : "匿名用户");
        user.setUserPhone(phone);
        user.setUserStatus("active");
        user.setRegisteredAt(LocalDateTime.now());
        return userRepository.save(user);
    }

    @Transactional
    public User updateUser(String userId, UserCreateRequest request) {
        User user = getUserOrThrow(userId);
        if (request.getUserName() != null) {
            user.setUserName(request.getUserName());
        }
        if (request.getUserPhone() != null && !request.getUserPhone().equals(user.getUserPhone())) {
            if (userRepository.existsByUserPhone(request.getUserPhone())) {
                throw new MovieException(400, "手机号已被使用");
            }
            user.setUserPhone(request.getUserPhone());
        }
        return userRepository.save(user);
    }

    @Transactional
    public void deleteUser(String userId) {
        User user = getUserOrThrow(userId);
        userRepository.delete(user);
    }

    public boolean exists(String userId) {
        return userRepository.existsByUserId(userId);
    }
}
