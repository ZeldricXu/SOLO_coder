package com.reviewsystem.service;

import com.reviewsystem.model.Comment;
import com.reviewsystem.repository.CommentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class SortService {

    private static final Logger logger = LoggerFactory.getLogger(SortService.class);

    @Autowired
    private CommentRepository commentRepository;

    public List<Comment> sortComments(String contentId, String sortType, int page, int size) {
        List<Comment> comments = commentRepository.findByContentIdAndCommentStatus(contentId, "published");

        switch (sortType.toLowerCase()) {
            case "newest":
                return sortByNewest(comments, page, size);
            case "oldest":
                return sortByOldest(comments, page, size);
            case "hot":
                return sortByHot(comments, page, size);
            case "quality":
                return sortByQuality(comments, page, size);
            case "recommend":
                return sortByRecommend(comments, page, size);
            case "most_likes":
                return sortByLikes(comments, page, size);
            case "most_replies":
                return sortByReplies(comments, page, size);
            default:
                return sortByNewest(comments, page, size);
        }
    }

    public List<Comment> sortByNewest(List<Comment> comments, int page, int size) {
        List<Comment> sorted = comments.stream()
                .sorted(Comparator.comparing(Comment::getCreatedAt).reversed())
                .collect(Collectors.toList());
        return paginate(sorted, page, size);
    }

    public List<Comment> sortByOldest(List<Comment> comments, int page, int size) {
        List<Comment> sorted = comments.stream()
                .sorted(Comparator.comparing(Comment::getCreatedAt))
                .collect(Collectors.toList());
        return paginate(sorted, page, size);
    }

    public List<Comment> sortByHot(List<Comment> comments, int page, int size) {
        List<Comment> sorted = comments.stream()
                .sorted((c1, c2) -> {
                    int score1 = calculateHeatScore(c1);
                    int score2 = calculateHeatScore(c2);
                    return Integer.compare(score2, score1);
                })
                .collect(Collectors.toList());
        return paginate(sorted, page, size);
    }

    public List<Comment> sortByQuality(List<Comment> comments, int page, int size) {
        List<Comment> sorted = comments.stream()
                .sorted((c1, c2) -> {
                    Integer q1 = c1.getQualityScore() != null ? c1.getQualityScore() : 0;
                    Integer q2 = c2.getQualityScore() != null ? c2.getQualityScore() : 0;
                    return q2.compareTo(q1);
                })
                .collect(Collectors.toList());
        return paginate(sorted, page, size);
    }

    public List<Comment> sortByRecommend(List<Comment> comments, int page, int size) {
        List<Comment> sorted = comments.stream()
                .sorted((c1, c2) -> {
                    Integer r1 = c1.getRecommendScore() != null ? c1.getRecommendScore() : 0;
                    Integer r2 = c2.getRecommendScore() != null ? c2.getRecommendScore() : 0;
                    return r2.compareTo(r1);
                })
                .collect(Collectors.toList());
        return paginate(sorted, page, size);
    }

    public List<Comment> sortByLikes(List<Comment> comments, int page, int size) {
        List<Comment> sorted = comments.stream()
                .sorted((c1, c2) -> {
                    Integer l1 = c1.getLikeCount() != null ? c1.getLikeCount() : 0;
                    Integer l2 = c2.getLikeCount() != null ? c2.getLikeCount() : 0;
                    return l2.compareTo(l1);
                })
                .collect(Collectors.toList());
        return paginate(sorted, page, size);
    }

    public List<Comment> sortByReplies(List<Comment> comments, int page, int size) {
        List<Comment> sorted = comments.stream()
                .sorted((c1, c2) -> {
                    Integer r1 = c1.getReplyCount() != null ? c1.getReplyCount() : 0;
                    Integer r2 = c2.getReplyCount() != null ? c2.getReplyCount() : 0;
                    return r2.compareTo(r1);
                })
                .collect(Collectors.toList());
        return paginate(sorted, page, size);
    }

    private int calculateHeatScore(Comment comment) {
        int likes = comment.getLikeCount() != null ? comment.getLikeCount() : 0;
        int replies = comment.getReplyCount() != null ? comment.getReplyCount() : 0;
        int quality = comment.getQualityScore() != null ? comment.getQualityScore() : 50;
        return likes * 3 + replies * 5 + quality;
    }

    private List<Comment> paginate(List<Comment> comments, int page, int size) {
        int start = page * size;
        int end = Math.min(start + size, comments.size());
        if (start >= comments.size()) {
            return new ArrayList<>();
        }
        return comments.subList(start, end);
    }

    public Map<String, List<Comment>> getMultiSortComments(String contentId, int size) {
        Map<String, List<Comment>> result = new LinkedHashMap<>();
        result.put("newest", sortComments(contentId, "newest", 0, size));
        result.put("hot", sortComments(contentId, "hot", 0, size));
        result.put("quality", sortComments(contentId, "quality", 0, size));
        result.put("recommend", sortComments(contentId, "recommend", 0, size));
        return result;
    }

    public Map<String, String> getSortTypes() {
        Map<String, String> sortTypes = new LinkedHashMap<>();
        sortTypes.put("newest", "最新评论");
        sortTypes.put("oldest", "最早评论");
        sortTypes.put("hot", "最热评论");
        sortTypes.put("quality", "质量优先");
        sortTypes.put("recommend", "推荐优先");
        sortTypes.put("most_likes", "最多点赞");
        sortTypes.put("most_replies", "最多回复");
        return sortTypes;
    }
}
