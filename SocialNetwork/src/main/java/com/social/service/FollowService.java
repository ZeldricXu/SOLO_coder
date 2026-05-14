package com.social.service;

import com.social.entity.Follow;
import com.social.entity.User;
import com.social.exception.SocialNetworkException;
import com.social.repository.FollowRepository;
import com.social.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class FollowService {

    @Autowired
    private FollowRepository followRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private HistoryService historyService;

    @Transactional
    public Follow followUser(String followerId, String followingId) {
        if (followerId == null || followingId == null) {
            throw new SocialNetworkException(400, "用户ID不能为空");
        }
        
        if (followerId.equals(followingId)) {
            throw new SocialNetworkException(400, "不能关注自己");
        }

        User follower = userService.getUserById(followerId);
        User following = userService.getUserById(followingId);

        if (!"active".equals(follower.getUserStatus())) {
            throw new SocialNetworkException(400, "用户状态不可用");
        }

        if (!"active".equals(following.getUserStatus())) {
            throw new SocialNetworkException(400, "目标用户状态不可用");
        }

        if (isFollowing(followerId, followingId)) {
            throw new SocialNetworkException(400, "已经关注了该用户");
        }

        Follow follow = new Follow();
        follow.setFollowId(IdGenerator.generateFollowId());
        follow.setFollowerId(followerId);
        follow.setFollowingId(followingId);
        follow.setFollowStatus("active");

        Follow savedFollow = followRepository.save(follow);
        
        historyService.recordFollow(followerId, followingId);

        return savedFollow;
    }

    @Transactional
    public void unfollowUser(String followerId, String followingId) {
        Follow follow = followRepository.findByFollowerIdAndFollowingIdAndFollowStatus(
                followerId, followingId, "active")
                .orElseThrow(() -> new SocialNetworkException(404, "关注关系不存在"));

        follow.setFollowStatus("inactive");
        followRepository.save(follow);
    }

    public boolean isFollowing(String followerId, String followingId) {
        return followRepository.existsByFollowerIdAndFollowingIdAndFollowStatus(followerId, followingId, "active");
    }

    public List<User> getFollowers(String userId) {
        List<Follow> follows = followRepository.findByFollowingIdAndFollowStatus(userId, "active");
        List<User> followers = new ArrayList<>();
        for (Follow f : follows) {
            try {
                followers.add(userService.getUserById(f.getFollowerId()));
            } catch (Exception e) {
            }
        }
        return followers;
    }

    public List<User> getFollowing(String userId) {
        List<Follow> follows = followRepository.findByFollowerIdAndFollowStatus(userId, "active");
        List<User> following = new ArrayList<>();
        for (Follow f : follows) {
            try {
                following.add(userService.getUserById(f.getFollowingId()));
            } catch (Exception e) {
            }
        }
        return following;
    }

    public long getFollowerCount(String userId) {
        return followRepository.findByFollowingIdAndFollowStatus(userId, "active").size();
    }

    public long getFollowingCount(String userId) {
        return followRepository.findByFollowerIdAndFollowStatus(userId, "active").size();
    }
}
