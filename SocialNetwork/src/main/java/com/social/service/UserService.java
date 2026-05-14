package com.social.service;

import com.social.entity.PrivacySetting;
import com.social.entity.User;
import com.social.exception.SocialNetworkException;
import com.social.repository.PrivacySettingRepository;
import com.social.repository.UserRepository;
import com.social.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class UserService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PrivacySettingRepository privacySettingRepository;

    @Transactional
    public User registerUser(String userName, String userPhone, String userAvatar) {
        if (userName == null || userName.trim().isEmpty()) {
            throw new SocialNetworkException(400, "用户名不能为空");
        }

        User user = new User();
        user.setUserId(IdGenerator.generateUserId());
        user.setUserName(userName);
        user.setUserPhone(userPhone);
        user.setUserAvatar(userAvatar);
        user.setUserStatus("active");
        user.setUserLevel("normal");
        user.setOnline(false);

        User savedUser = userRepository.save(user);

        PrivacySetting privacySetting = new PrivacySetting();
        privacySetting.setPrivacyId(IdGenerator.generatePrivacyId());
        privacySetting.setUserId(savedUser.getUserId());
        privacySettingRepository.save(privacySetting);

        return savedUser;
    }

    public User getUserById(String userId) {
        return userRepository.findByUserId(userId)
                .orElseThrow(() -> new SocialNetworkException(404, "用户不存在: " + userId));
    }

    public List<User> getAllActiveUsers() {
        return userRepository.findByUserStatus("active");
    }

    @Transactional
    public User updateUserInfo(String userId, String userName, String userPhone, String userAvatar) {
        User user = getUserById(userId);
        
        if (userName != null && !userName.trim().isEmpty()) {
            user.setUserName(userName);
        }
        if (userPhone != null) {
            user.setUserPhone(userPhone);
        }
        if (userAvatar != null) {
            user.setUserAvatar(userAvatar);
        }

        return userRepository.save(user);
    }

    @Transactional
    public User updateUserStatus(String userId, String status) {
        User user = getUserById(userId);
        user.setUserStatus(status);
        return userRepository.save(user);
    }

    @Transactional
    public User setUserOnline(String userId, boolean online) {
        User user = getUserById(userId);
        user.setOnline(online);
        return userRepository.save(user);
    }

    public long countActiveUsers() {
        return userRepository.countByUserStatus("active");
    }
}
