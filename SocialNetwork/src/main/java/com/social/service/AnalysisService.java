package com.social.service;

import com.social.entity.SocialStat;
import com.social.repository.SocialStatRepository;
import com.social.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class AnalysisService {

    @Autowired
    private SocialStatRepository socialStatRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private FriendService friendService;

    @Autowired
    private MessageService messageService;

    @Autowired
    private PostService postService;

    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

    public String getCurrentMonth() {
        return LocalDate.now().format(MONTH_FORMAT);
    }

    @Transactional
    public SocialStat getOrCreateCurrentStat() {
        String month = getCurrentMonth();
        Optional<SocialStat> existingStat = socialStatRepository.findByStatMonth(month);
        
        if (existingStat.isPresent()) {
            return existingStat.get();
        }

        SocialStat stat = new SocialStat();
        stat.setStatId(IdGenerator.generateStatId());
        stat.setStatMonth(month);
        stat.setUserCount(0);
        stat.setFriendshipCount(0);
        stat.setMessageCount(0);
        stat.setPostCount(0);
        stat.setInteractionCount(0);

        return socialStatRepository.save(stat);
    }

    @Transactional
    public void incrementUserCount() {
        SocialStat stat = getOrCreateCurrentStat();
        stat.setUserCount(stat.getUserCount() + 1);
        socialStatRepository.save(stat);
    }

    @Transactional
    public void incrementFriendshipCount() {
        SocialStat stat = getOrCreateCurrentStat();
        stat.setFriendshipCount(stat.getFriendshipCount() + 1);
        socialStatRepository.save(stat);
    }

    @Transactional
    public void incrementMessageCount() {
        SocialStat stat = getOrCreateCurrentStat();
        stat.setMessageCount(stat.getMessageCount() + 1);
        socialStatRepository.save(stat);
    }

    @Transactional
    public void incrementPostCount() {
        SocialStat stat = getOrCreateCurrentStat();
        stat.setPostCount(stat.getPostCount() + 1);
        socialStatRepository.save(stat);
    }

    @Transactional
    public void incrementInteractionCount() {
        SocialStat stat = getOrCreateCurrentStat();
        stat.setInteractionCount(stat.getInteractionCount() + 1);
        socialStatRepository.save(stat);
    }

    public Map<String, Object> getSocialStatistics() {
        Map<String, Object> stats = new HashMap<>();
        
        SocialStat currentMonthStat = getOrCreateCurrentStat();
        stats.put("currentMonth", currentMonthStat.getStatMonth());
        stats.put("currentMonthStats", currentMonthStat);
        
        stats.put("totalActiveUsers", userService.countActiveUsers());
        stats.put("totalFriendships", friendService.countAcceptedFriendships());
        stats.put("totalMessages", messageService.countTotalMessages());
        stats.put("totalPosts", postService.countTotalPosts());
        stats.put("totalInteractions", postService.countTotalInteractions());
        
        return stats;
    }

    public SocialStat getMonthlyStats(String month) {
        return socialStatRepository.findByStatMonth(month)
                .orElse(null);
    }

    public Map<String, Object> analyzeUserRelations(String userId) {
        Map<String, Object> relations = new HashMap<>();
        
        relations.put("totalFriends", friendService.getFriends(userId).size());
        relations.put("totalFollowers", 0);
        relations.put("totalFollowing", 0);
        
        try {
            relations.put("totalPosts", postService.getUserPosts(userId).size());
        } catch (Exception e) {
            relations.put("totalPosts", 0);
        }
        
        return relations;
    }
}
