package com.social.service;

import com.social.entity.PrivacySetting;
import com.social.exception.SocialNetworkException;
import com.social.repository.FollowRepository;
import com.social.repository.PrivacySettingRepository;
import com.social.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PrivacyService {

    @Autowired
    private PrivacySettingRepository privacySettingRepository;

    @Autowired
    private PrivacyLevelManager privacyLevelManager;

    @Autowired
    private FriendService friendService;

    @Autowired
    private FollowService followService;

    @Autowired
    private FollowRepository followRepository;

    public PrivacySetting getPrivacySetting(String userId) {
        return privacySettingRepository.findByUserId(userId)
                .orElseGet(() -> {
                    PrivacySetting ps = new PrivacySetting();
                    ps.setPrivacyId(IdGenerator.generatePrivacyId());
                    ps.setUserId(userId);
                    ps.setFriendRequestPolicy(privacyLevelManager.getDefaultFriendRequestPolicy());
                    ps.setMessagePolicy(privacyLevelManager.getDefaultMessagePolicy());
                    ps.setPostVisibility(privacyLevelManager.getDefaultPostVisibility());
                    ps.setProfileVisibility(privacyLevelManager.getDefaultProfileVisibility());
                    return privacySettingRepository.save(ps);
                });
    }

    @Transactional
    public PrivacySetting updatePrivacySetting(String userId, 
            String friendRequestPolicy, 
            String messagePolicy, 
            String postVisibility, 
            String profileVisibility) {
        
        PrivacySetting setting = getPrivacySetting(userId);
        
        if (friendRequestPolicy != null) {
            if (!privacyLevelManager.isValidFriendRequestPolicy(friendRequestPolicy)) {
                throw new SocialNetworkException(400, "无效的好友请求策略: " + friendRequestPolicy);
            }
            setting.setFriendRequestPolicy(friendRequestPolicy);
        }
        if (messagePolicy != null) {
            if (!privacyLevelManager.isValidMessagePolicy(messagePolicy)) {
                throw new SocialNetworkException(400, "无效的消息策略: " + messagePolicy);
            }
            setting.setMessagePolicy(messagePolicy);
        }
        if (postVisibility != null) {
            if (!privacyLevelManager.isValidPostVisibility(postVisibility)) {
                throw new SocialNetworkException(400, "无效的动态可见性: " + postVisibility);
            }
            setting.setPostVisibility(postVisibility);
        }
        if (profileVisibility != null) {
            if (!privacyLevelManager.isValidProfileVisibility(profileVisibility)) {
                throw new SocialNetworkException(400, "无效的资料可见性: " + profileVisibility);
            }
            setting.setProfileVisibility(profileVisibility);
        }

        return privacySettingRepository.save(setting);
    }

    public boolean canReceiveFriendRequests(String targetUserId) {
        PrivacySetting setting = getPrivacySetting(targetUserId);
        String policy = setting.getFriendRequestPolicy();
        
        if ("none".equals(policy)) {
            return false;
        }
        
        return true;
    }

    public boolean canReceiveMessage(String fromUserId, String toUserId, boolean isFriend) {
        PrivacySetting setting = getPrivacySetting(toUserId);
        String policy = setting.getMessagePolicy();
        
        if ("none".equals(policy)) {
            return false;
        } else if ("friends_only".equals(policy)) {
            return isFriend;
        } else if ("followers_only".equals(policy)) {
            return followService.isFollowing(fromUserId, toUserId);
        }
        
        return true;
    }

    public boolean canViewPost(String viewerId, String authorId) {
        if (viewerId != null && viewerId.equals(authorId)) {
            return true;
        }
        
        PrivacySetting setting = getPrivacySetting(authorId);
        String visibility = setting.getPostVisibility();
        
        if ("public".equals(visibility)) {
            return true;
        } else if ("friends_only".equals(visibility)) {
            return friendService.isFriend(viewerId, authorId);
        } else if ("followers_only".equals(visibility)) {
            return followService.isFollowing(viewerId, authorId);
        } else if ("private".equals(visibility)) {
            return viewerId != null && viewerId.equals(authorId);
        }
        
        return true;
    }

    public boolean canViewProfile(String viewerId, String targetUserId) {
        if (viewerId != null && viewerId.equals(targetUserId)) {
            return true;
        }
        
        PrivacySetting setting = getPrivacySetting(targetUserId);
        String visibility = setting.getProfileVisibility();
        
        if ("public".equals(visibility)) {
            return true;
        } else if ("friends_only".equals(visibility)) {
            return friendService.isFriend(viewerId, targetUserId);
        } else if ("private".equals(visibility)) {
            return viewerId != null && viewerId.equals(targetUserId);
        }
        
        return true;
    }

    public String getPostVisibility(String userId) {
        PrivacySetting setting = getPrivacySetting(userId);
        return setting.getPostVisibility();
    }

    public String getFriendRequestPolicy(String userId) {
        PrivacySetting setting = getPrivacySetting(userId);
        return setting.getFriendRequestPolicy();
    }

    public String getMessagePolicy(String userId) {
        PrivacySetting setting = getPrivacySetting(userId);
        return setting.getMessagePolicy();
    }

    public String getProfileVisibility(String userId) {
        PrivacySetting setting = getPrivacySetting(userId);
        return setting.getProfileVisibility();
    }
}
