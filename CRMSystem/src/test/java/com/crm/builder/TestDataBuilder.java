package com.crm.builder;

import com.crm.entity.*;

import java.time.LocalDateTime;

public class TestDataBuilder {

    public static Customer buildPotentialCustomer(String customerId) {
        Customer customer = new Customer();
        customer.setCustomerId(customerId);
        customer.setCustomerName("测试客户");
        customer.setCustomerType("enterprise");
        customer.setCustomerStatus("potential");
        customer.setCustomerSource("marketing");
        customer.setCustomerContact("13800138000");
        customer.setCustomerAddress("北京市朝阳区");
        customer.setFollowCount(0);
        customer.setOpportunityCount(0);
        customer.setCreatedAt(LocalDateTime.now().minusDays(7));
        customer.setUpdatedAt(LocalDateTime.now());
        return customer;
    }

    public static Customer buildPotentialCustomer() {
        return buildPotentialCustomer("customer_test_001");
    }

    public static Customer buildVIPCustomer(String customerId) {
        Customer customer = buildPotentialCustomer(customerId);
        customer.setCustomerName("VIP测试客户");
        return customer;
    }

    public static Customer buildVIPCustomer() {
        return buildVIPCustomer("customer_vip_001");
    }

    public static Customer buildInterestedCustomer(String customerId) {
        Customer customer = buildPotentialCustomer(customerId);
        customer.setCustomerStatus("interested");
        customer.setFollowCount(2);
        return customer;
    }

    public static Customer buildInterestedCustomer() {
        return buildInterestedCustomer("customer_interested_001");
    }

    public static Customer buildDealCustomer(String customerId) {
        Customer customer = buildPotentialCustomer(customerId);
        customer.setCustomerStatus("deal");
        customer.setFollowCount(5);
        customer.setOpportunityCount(2);
        return customer;
    }

    public static Customer buildDealCustomer() {
        return buildDealCustomer("customer_deal_001");
    }

    public static Customer buildRejectedCustomer(String customerId) {
        Customer customer = buildPotentialCustomer(customerId);
        customer.setCustomerStatus("rejected");
        customer.setFollowCount(3);
        return customer;
    }

    public static Follow buildPhoneFollow(String customerId) {
        Follow follow = new Follow();
        follow.setFollowId("follow_test_001");
        follow.setCustomerId(customerId);
        follow.setSalesId("sales_001");
        follow.setFollowType("phone");
        follow.setFollowContent("电话沟通产品需求");
        follow.setFollowResult("interested");
        follow.setFollowTime(LocalDateTime.now());
        follow.setNextFollow(LocalDateTime.now().plusDays(7));
        follow.setCreatedAt(LocalDateTime.now());
        return follow;
    }

    public static Follow buildPhoneFollow() {
        return buildPhoneFollow("customer_test_001");
    }

    public static Follow buildVisitFollow(String customerId) {
        Follow follow = buildPhoneFollow(customerId);
        follow.setFollowId("follow_test_002");
        follow.setFollowType("visit");
        follow.setFollowContent("拜访客户，演示产品");
        return follow;
    }

    public static Follow buildDealFollow(String customerId) {
        Follow follow = buildPhoneFollow(customerId);
        follow.setFollowId("follow_deal_001");
        follow.setFollowResult("deal");
        follow.setNextFollow(null);
        return follow;
    }

    public static Follow buildRejectedFollow(String customerId) {
        Follow follow = buildPhoneFollow(customerId);
        follow.setFollowId("follow_rejected_001");
        follow.setFollowResult("rejected");
        follow.setNextFollow(null);
        return follow;
    }

    public static Opportunity buildInitialOpportunity(String customerId) {
        Opportunity opportunity = new Opportunity();
        opportunity.setOpportunityId("opp_test_001");
        opportunity.setCustomerId(customerId);
        opportunity.setSalesId("sales_001");
        opportunity.setOpportunityAmount(50000.0);
        opportunity.setOpportunityStage("initial");
        opportunity.setOpportunityStatus("following");
        opportunity.setOpportunityProb(10);
        opportunity.setCreatedAt(LocalDateTime.now().minusDays(3));
        opportunity.setUpdatedAt(LocalDateTime.now().minusDays(3));
        return opportunity;
    }

    public static Opportunity buildInitialOpportunity() {
        return buildInitialOpportunity("customer_test_001");
    }

    public static Opportunity buildNegotiationOpportunity(String customerId) {
        Opportunity opportunity = buildInitialOpportunity(customerId);
        opportunity.setOpportunityId("opp_test_002");
        opportunity.setOpportunityStage("negotiation");
        opportunity.setOpportunityProb(60);
        return opportunity;
    }

    public static Opportunity buildLargeAmountOpportunity(String customerId) {
        Opportunity opportunity = buildInitialOpportunity(customerId);
        opportunity.setOpportunityId("opp_large_001");
        opportunity.setOpportunityAmount(500000.0);
        opportunity.setOpportunityStage("proposal");
        opportunity.setOpportunityProb(40);
        return opportunity;
    }

    public static Opportunity buildSmallAmountOpportunity(String customerId) {
        Opportunity opportunity = buildInitialOpportunity(customerId);
        opportunity.setOpportunityId("opp_small_001");
        opportunity.setOpportunityAmount(10000.0);
        return opportunity;
    }

    public static Opportunity buildSuccessOpportunity(String customerId) {
        Opportunity opportunity = buildInitialOpportunity(customerId);
        opportunity.setOpportunityId("opp_success_001");
        opportunity.setOpportunityStatus("success");
        opportunity.setOpportunityStage("closing");
        opportunity.setOpportunityProb(80);
        opportunity.setDealTime(LocalDateTime.now());
        return opportunity;
    }

    public static Opportunity buildFailedOpportunity(String customerId) {
        Opportunity opportunity = buildInitialOpportunity(customerId);
        opportunity.setOpportunityId("opp_failed_001");
        opportunity.setOpportunityStatus("failed");
        opportunity.setFailReason("客户选择了竞品");
        return opportunity;
    }

    public static Opportunity buildStaleOpportunity(String customerId, int daysWithoutUpdate) {
        Opportunity opportunity = buildInitialOpportunity(customerId);
        opportunity.setOpportunityId("opp_stale_001");
        opportunity.setUpdatedAt(LocalDateTime.now().minusDays(daysWithoutUpdate));
        return opportunity;
    }

    public static Category buildVIPCategory() {
        Category category = new Category();
        category.setCategoryId("category_vip");
        category.setCategoryName("VIP客户");
        category.setCategoryType("value");
        category.setCategoryLevel(1);
        category.setCategoryStatus("active");
        category.setCreatedAt(LocalDateTime.now());
        return category;
    }

    public static Category buildRegularCategory() {
        Category category = new Category();
        category.setCategoryId("category_regular");
        category.setCategoryName("普通客户");
        category.setCategoryType("value");
        category.setCategoryLevel(3);
        category.setCategoryStatus("active");
        category.setCreatedAt(LocalDateTime.now());
        return category;
    }

    public static Tag buildTechIndustryTag() {
        Tag tag = new Tag();
        tag.setTagId("tag_tech");
        tag.setTagName("科技行业");
        tag.setTagType("industry");
        tag.setTagStatus("active");
        tag.setCreatedAt(LocalDateTime.now());
        return tag;
    }

    public static Reminder buildFollowReminder(String customerId) {
        Reminder reminder = new Reminder();
        reminder.setReminderId("reminder_test_001");
        reminder.setCustomerId(customerId);
        reminder.setSalesId("sales_001");
        reminder.setReminderType("follow_remind");
        reminder.setReminderTime(LocalDateTime.now().plusDays(1));
        reminder.setReminderStatus("pending");
        reminder.setReminderContent("请跟进客户");
        reminder.setCreatedAt(LocalDateTime.now());
        return reminder;
    }

    public static Reminder buildOverdueReminder(String customerId) {
        Reminder reminder = buildFollowReminder(customerId);
        reminder.setReminderId("reminder_overdue_001");
        reminder.setReminderTime(LocalDateTime.now().minusHours(1));
        return reminder;
    }

    public static Statistics buildMonthlyStatistics(String month) {
        Statistics stats = new Statistics();
        stats.setStatId("stat_" + month);
        stats.setStatMonth(month);
        stats.setCustomerCount(50);
        stats.setFollowCount(100);
        stats.setOpportunityCount(20);
        stats.setDealAmount(500000.0);
        stats.setSuccessCount(10);
        stats.setFailCount(5);
        return stats;
    }

    public static Contact buildPrimaryContact(String customerId) {
        Contact contact = new Contact();
        contact.setCustomerId(customerId);
        contact.setContactName("张三");
        contact.setContactPhone("13800138001");
        contact.setContactEmail("zhangsan@example.com");
        contact.setContactPosition("销售总监");
        contact.setIsPrimary(true);
        contact.setCreatedAt(LocalDateTime.now());
        return contact;
    }

    public static History buildFollowHistory(String customerId, String followId) {
        History history = new History();
        history.setCustomerId(customerId);
        history.setHistoryType("follow");
        history.setRelatedId(followId);
        history.setAction("create");
        history.setDetail("创建跟进记录");
        history.setOperator("sales_001");
        history.setCreatedAt(LocalDateTime.now());
        return history;
    }
}
