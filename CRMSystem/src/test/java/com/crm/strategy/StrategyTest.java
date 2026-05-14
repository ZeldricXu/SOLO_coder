package com.crm.strategy;

import com.crm.builder.TestDataBuilder;
import com.crm.entity.Category;
import com.crm.entity.Customer;
import com.crm.entity.Opportunity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("策略单元测试")
class StrategyTest {

    private DefaultReminderTimeStrategy reminderTimeStrategy;
    private DefaultOpportunityAlertStrategy opportunityAlertStrategy;
    private Customer potentialCustomer;
    private Customer vipCustomer;
    private Category vipCategory;
    private Category regularCategory;
    private Opportunity largeAmountOpportunity;
    private Opportunity smallAmountOpportunity;

    @BeforeEach
    void setUp() {
        reminderTimeStrategy = new DefaultReminderTimeStrategy();
        opportunityAlertStrategy = new DefaultOpportunityAlertStrategy();
        potentialCustomer = TestDataBuilder.buildPotentialCustomer("customer_test_001");
        vipCustomer = TestDataBuilder.buildVIPCustomer("customer_vip_001");
        vipCategory = TestDataBuilder.buildVIPCategory();
        regularCategory = TestDataBuilder.buildRegularCategory();
        largeAmountOpportunity = TestDataBuilder.buildLargeAmountOpportunity("customer_test_001");
        smallAmountOpportunity = TestDataBuilder.buildSmallAmountOpportunity("customer_test_001");
    }

    @Test
    @DisplayName("提醒时间策略 - VIP客户提前48小时")
    void testCalculateReminderTime_VIPCustomer() {
        LocalDateTime baseTime = LocalDateTime.now().plusDays(7);
        List<Category> categories = Collections.singletonList(vipCategory);

        LocalDateTime reminderTime = reminderTimeStrategy.calculateReminderTime(vipCustomer, baseTime, categories);

        assertEquals(baseTime.minusHours(48), reminderTime);
    }

    @Test
    @DisplayName("提醒时间策略 - 普通客户提前24小时")
    void testCalculateReminderTime_RegularCustomer() {
        LocalDateTime baseTime = LocalDateTime.now().plusDays(7);
        List<Category> categories = Collections.singletonList(regularCategory);

        LocalDateTime reminderTime = reminderTimeStrategy.calculateReminderTime(potentialCustomer, baseTime, categories);

        assertEquals(baseTime.minusHours(24), reminderTime);
    }

    @Test
    @DisplayName("提醒时间策略 - 无分类时按普通客户处理")
    void testCalculateReminderTime_NoCategories() {
        LocalDateTime baseTime = LocalDateTime.now().plusDays(7);

        LocalDateTime reminderTime = reminderTimeStrategy.calculateReminderTime(potentialCustomer, baseTime, Collections.emptyList());

        assertEquals(baseTime.minusHours(24), reminderTime);
    }

    @Test
    @DisplayName("VIP客户判断 - 有VIP分类返回true")
    void testIsVIPCustomer_WithVIPCategory() {
        List<Category> categories = Collections.singletonList(vipCategory);

        assertTrue(reminderTimeStrategy.isVIPCustomer(vipCustomer, categories));
    }

    @Test
    @DisplayName("VIP客户判断 - 只有普通分类返回false")
    void testIsVIPCustomer_OnlyRegularCategory() {
        List<Category> categories = Collections.singletonList(regularCategory);

        assertFalse(reminderTimeStrategy.isVIPCustomer(potentialCustomer, categories));
    }

    @Test
    @DisplayName("VIP客户判断 - 无分类返回false")
    void testIsVIPCustomer_NoCategories() {
        assertFalse(reminderTimeStrategy.isVIPCustomer(potentialCustomer, Collections.emptyList()));
    }

    @Test
    @DisplayName("VIP客户判断 - 等级1返回true")
    void testIsVIPCustomer_Level1Category() {
        Category level1Category = new Category();
        level1Category.setCategoryId("cat_level1");
        level1Category.setCategoryName("高价值客户");
        level1Category.setCategoryLevel(1);
        
        List<Category> categories = Collections.singletonList(level1Category);

        assertTrue(reminderTimeStrategy.isVIPCustomer(potentialCustomer, categories));
    }

    @Test
    @DisplayName("提醒时间策略常量验证 - VIP提前48小时")
    void testReminderTimeConstants_VIP() {
        assertEquals(48, reminderTimeStrategy.getVIPAdvanceHours());
    }

    @Test
    @DisplayName("提醒时间策略常量验证 - 普通客户提前24小时")
    void testReminderTimeConstants_Regular() {
        assertEquals(24, reminderTimeStrategy.getRegularAdvanceHours());
    }

    @Test
    @DisplayName("机会预警策略 - 大额机会阈值3天")
    void testGetAlertThreshold_LargeAmount() {
        assertEquals(3, opportunityAlertStrategy.getAlertThresholdDays(largeAmountOpportunity));
    }

    @Test
    @DisplayName("机会预警策略 - 小额机会阈值7天")
    void testGetAlertThreshold_SmallAmount() {
        assertEquals(7, opportunityAlertStrategy.getAlertThresholdDays(smallAmountOpportunity));
    }

    @Test
    @DisplayName("机会预警策略 - 大额机会常量验证")
    void testOpportunityAlertConstants_LargeThreshold() {
        assertEquals(100000.0, opportunityAlertStrategy.getLargeAmountThreshold());
        assertEquals(3, opportunityAlertStrategy.getLargeAmountAlertDays());
    }

    @Test
    @DisplayName("机会预警策略 - 小额机会常量验证")
    void testOpportunityAlertConstants_SmallThreshold() {
        assertEquals(7, opportunityAlertStrategy.getSmallAmountAlertDays());
    }

    @Test
    @DisplayName("机会预警判断 - 已成交机会不预警")
    void testShouldAlert_SuccessOpportunity() {
        Opportunity successOpportunity = TestDataBuilder.buildSuccessOpportunity("customer_test_001");
        
        assertFalse(opportunityAlertStrategy.shouldAlert(successOpportunity));
    }

    @Test
    @DisplayName("机会预警判断 - 已失败机会不预警")
    void testShouldAlert_FailedOpportunity() {
        Opportunity failedOpportunity = TestDataBuilder.buildFailedOpportunity("customer_test_001");
        
        assertFalse(opportunityAlertStrategy.shouldAlert(failedOpportunity));
    }

    @Test
    @DisplayName("机会预警判断 - 大额机会3天未更新触发预警")
    void testShouldAlert_LargeAmount3Days() {
        Opportunity staleLargeOpportunity = TestDataBuilder.buildStaleOpportunity("customer_test_001", 3);
        staleLargeOpportunity.setOpportunityAmount(500000.0);
        
        assertTrue(opportunityAlertStrategy.shouldAlert(staleLargeOpportunity));
    }

    @Test
    @DisplayName("机会预警判断 - 小额机会3天未更新不触发预警")
    void testShouldAlert_SmallAmount3Days() {
        Opportunity freshSmallOpportunity = TestDataBuilder.buildStaleOpportunity("customer_test_001", 3);
        freshSmallOpportunity.setOpportunityAmount(10000.0);
        
        assertFalse(opportunityAlertStrategy.shouldAlert(freshSmallOpportunity));
    }

    @Test
    @DisplayName("机会预警判断 - 小额机会7天未更新触发预警")
    void testShouldAlert_SmallAmount7Days() {
        Opportunity staleSmallOpportunity = TestDataBuilder.buildStaleOpportunity("customer_test_001", 7);
        staleSmallOpportunity.setOpportunityAmount(10000.0);
        
        assertTrue(opportunityAlertStrategy.shouldAlert(staleSmallOpportunity));
    }

    @Test
    @DisplayName("机会预警判断 - 无更新时间不预警")
    void testShouldAlert_NoUpdatedAt() {
        Opportunity opportunity = TestDataBuilder.buildInitialOpportunity("customer_test_001");
        opportunity.setUpdatedAt(null);
        
        assertFalse(opportunityAlertStrategy.shouldAlert(opportunity));
    }
}
