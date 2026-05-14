package com.social.service;

import com.social.entity.Interaction;
import com.social.entity.Post;
import com.social.entity.User;
import com.social.exception.SocialNetworkException;
import com.social.repository.InteractionRepository;
import com.social.repository.PostRepository;
import com.social.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class PostService {

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private InteractionRepository interactionRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private PrivacyService privacyService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private AnalysisService analysisService;

    @Autowired
    private PostPushService postPushService;

    @Transactional
    public Post createPost(String userId, String postContent, String postType) {
        if (userId == null) {
            throw new SocialNetworkException(400, "用户ID不能为空");
        }
        
        if (postContent == null || postContent.trim().isEmpty()) {
            throw new SocialNetworkException(400, "动态内容不能为空");
        }

        User user = userService.getUserById(userId);
        
        if (!"active".equals(user.getUserStatus())) {
            throw new SocialNetworkException(400, "用户状态不可用");
        }

        Post post = new Post();
        post.setPostId(IdGenerator.generatePostId());
        post.setUserId(userId);
        post.setPostContent(postContent);
        post.setPostType(postType != null ? postType : "text");
        post.setPostLikes(0);
        post.setPostComments(0);
        post.setPostStatus("published");

        Post savedPost = postRepository.save(post);
        
        analysisService.incrementPostCount();
        historyService.recordPost(userId, savedPost.getPostId());

        postPushService.asyncPushToFollowers(savedPost);

        return savedPost;
    }

    @Transactional
    public Post likePost(String postId, String userId) {
        Post post = getPostById(postId);
        User user = userService.getUserById(userId);

        if (!"active".equals(user.getUserStatus())) {
            throw new SocialNetworkException(400, "用户状态不可用");
        }

        Interaction interaction = new Interaction();
        interaction.setInteractionId(IdGenerator.generateInteractionId());
        interaction.setPostId(postId);
        interaction.setUserId(userId);
        interaction.setInteractionType("like");
        interactionRepository.save(interaction);

        post.setPostLikes(post.getPostLikes() + 1);
        Post savedPost = postRepository.save(post);
        
        analysisService.incrementInteractionCount();

        return savedPost;
    }

    @Transactional
    public Post commentPost(String postId, String userId, String commentContent) {
        Post post = getPostById(postId);
        User user = userService.getUserById(userId);

        if (!"active".equals(user.getUserStatus())) {
            throw new SocialNetworkException(400, "用户状态不可用");
        }

        if (commentContent == null || commentContent.trim().isEmpty()) {
            throw new SocialNetworkException(400, "评论内容不能为空");
        }

        Interaction interaction = new Interaction();
        interaction.setInteractionId(IdGenerator.generateInteractionId());
        interaction.setPostId(postId);
        interaction.setUserId(userId);
        interaction.setInteractionType("comment");
        interaction.setCommentContent(commentContent);
        interactionRepository.save(interaction);

        post.setPostComments(post.getPostComments() + 1);
        Post savedPost = postRepository.save(post);
        
        analysisService.incrementInteractionCount();

        return savedPost;
    }

    public Post getPostById(String postId) {
        return postRepository.findByPostId(postId)
                .orElseThrow(() -> new SocialNetworkException(404, "动态不存在: " + postId));
    }

    public List<Post> getUserPosts(String userId) {
        return postRepository.findByUserIdOrderByPostTimeDesc(userId);
    }

    public List<Post> getAllPublishedPosts() {
        return postRepository.findByPostStatusOrderByPostTimeDesc("published");
    }

    public List<Interaction> getPostInteractions(String postId) {
        return interactionRepository.findByPostIdOrderByInteractionTimeDesc(postId);
    }

    public List<Interaction> getUserInteractions(String userId) {
        return interactionRepository.findByUserIdOrderByInteractionTimeDesc(userId);
    }

    public long countTotalPosts() {
        return postRepository.count();
    }

    public long countTotalInteractions() {
        return interactionRepository.count();
    }
}
