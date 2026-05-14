package com.fitnesscenter.builder;

import com.fitnesscenter.dto.BookingRequest;
import com.fitnesscenter.dto.MemberRequest;
import com.fitnesscenter.dto.PlanRequest;
import com.fitnesscenter.dto.TrainingRequest;
import com.fitnesscenter.model.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class TestDataBuilder {

    public static final String VIP_MEMBER_LEVEL = "vip";
    public static final String REGULAR_MEMBER_LEVEL = "regular";
    public static final String ACTIVE_MEMBER_STATUS = "active";
    public static final String EXPIRED_MEMBER_STATUS = "expired";
    public static final String FROZEN_MEMBER_STATUS = "frozen";

    public static final String COURSE_STATUS_SCHEDULED = "scheduled";
    public static final String COURSE_STATUS_CANCELLED = "cancelled";

    public static final String BOOKING_STATUS_CONFIRMED = "confirmed";
    public static final String BOOKING_STATUS_CANCELLED = "cancelled";

    public static final String PLAN_STATUS_IN_PROGRESS = "in_progress";
    public static final String PLAN_STATUS_COMPLETED = "completed";

    public static final String TRAINING_INTENSITY_LOW = "low";
    public static final String TRAINING_INTENSITY_MEDIUM = "medium";
    public static final String TRAINING_INTENSITY_HIGH = "high";

    public static final String MEMBER_TYPE_ANNUAL = "annual";
    public static final String MEMBER_TYPE_MONTHLY = "monthly";

    public static final String COACH_STATUS_AVAILABLE = "available";
    public static final String COACH_STATUS_UNAVAILABLE = "unavailable";

    public static final String GYM_STATUS_ACTIVE = "active";
    public static final String GYM_STATUS_INACTIVE = "inactive";

    public static final String EQUIPMENT_STATUS_AVAILABLE = "available";
    public static final String EQUIPMENT_STATUS_IN_USE = "in_use";
    public static final String EQUIPMENT_STATUS_MAINTENANCE = "maintenance";

    public static String generateTestId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    public static Member buildVipMember() {
        Member member = new Member();
        member.setMemberId(generateTestId("MEMBER"));
        member.setMemberName("VIP会员_测试");
        member.setMemberPhone("13800138001");
        member.setMemberType(MEMBER_TYPE_ANNUAL);
        member.setMemberStatus(ACTIVE_MEMBER_STATUS);
        member.setMemberLevel(VIP_MEMBER_LEVEL);
        member.setRegisteredAt(Instant.now().minus(30, ChronoUnit.DAYS));
        member.setExpireAt(Instant.now().plus(335, ChronoUnit.DAYS));
        member.setBookingCount(0);
        member.setTrainingCount(0);
        member.setTotalCalories(0);
        return member;
    }

    public static Member buildRegularMember() {
        Member member = new Member();
        member.setMemberId(generateTestId("MEMBER"));
        member.setMemberName("普通会员_测试");
        member.setMemberPhone("13800138002");
        member.setMemberType(MEMBER_TYPE_MONTHLY);
        member.setMemberStatus(ACTIVE_MEMBER_STATUS);
        member.setMemberLevel(REGULAR_MEMBER_LEVEL);
        member.setRegisteredAt(Instant.now().minus(15, ChronoUnit.DAYS));
        member.setExpireAt(Instant.now().plus(15, ChronoUnit.DAYS));
        member.setBookingCount(0);
        member.setTrainingCount(0);
        member.setTotalCalories(0);
        return member;
    }

    public static Member buildExpiredMember() {
        Member member = new Member();
        member.setMemberId(generateTestId("MEMBER"));
        member.setMemberName("过期会员_测试");
        member.setMemberPhone("13800138003");
        member.setMemberType(MEMBER_TYPE_ANNUAL);
        member.setMemberStatus(EXPIRED_MEMBER_STATUS);
        member.setMemberLevel(REGULAR_MEMBER_LEVEL);
        member.setRegisteredAt(Instant.now().minus(400, ChronoUnit.DAYS));
        member.setExpireAt(Instant.now().minus(35, ChronoUnit.DAYS));
        member.setBookingCount(5);
        member.setTrainingCount(3);
        member.setTotalCalories(1500);
        return member;
    }

    public static Member buildFrozenMember() {
        Member member = new Member();
        member.setMemberId(generateTestId("MEMBER"));
        member.setMemberName("冻结会员_测试");
        member.setMemberPhone("13800138004");
        member.setMemberType(MEMBER_TYPE_ANNUAL);
        member.setMemberStatus(FROZEN_MEMBER_STATUS);
        member.setMemberLevel(REGULAR_MEMBER_LEVEL);
        member.setRegisteredAt(Instant.now().minus(60, ChronoUnit.DAYS));
        member.setExpireAt(Instant.now().plus(300, ChronoUnit.DAYS));
        member.setBookingCount(2);
        member.setTrainingCount(1);
        member.setTotalCalories(300);
        return member;
    }

    public static Member buildActiveMember() {
        Member member = new Member();
        member.setMemberId(generateTestId("MEMBER"));
        member.setMemberName("活跃会员_测试");
        member.setMemberPhone("13800138005");
        member.setMemberType(MEMBER_TYPE_ANNUAL);
        member.setMemberStatus(ACTIVE_MEMBER_STATUS);
        member.setMemberLevel(VIP_MEMBER_LEVEL);
        member.setRegisteredAt(Instant.now().minus(90, ChronoUnit.DAYS));
        member.setExpireAt(Instant.now().plus(270, ChronoUnit.DAYS));
        member.setBookingCount(10);
        member.setTrainingCount(8);
        member.setTotalCalories(4500);
        return member;
    }

    public static Member buildInactiveMember() {
        Member member = new Member();
        member.setMemberId(generateTestId("MEMBER"));
        member.setMemberName("不活跃会员_测试");
        member.setMemberPhone("13800138006");
        member.setMemberType(MEMBER_TYPE_MONTHLY);
        member.setMemberStatus(ACTIVE_MEMBER_STATUS);
        member.setMemberLevel(REGULAR_MEMBER_LEVEL);
        member.setRegisteredAt(Instant.now().minus(30, ChronoUnit.DAYS));
        member.setExpireAt(Instant.now().minus(1, ChronoUnit.DAYS));
        member.setBookingCount(1);
        member.setTrainingCount(0);
        member.setTotalCalories(0);
        return member;
    }

    public static Course buildYogaCourse() {
        Course course = new Course();
        course.setCourseId(generateTestId("COURSE"));
        course.setCourseName("瑜伽课程");
        course.setCourseType("yoga");
        course.setCourseCoach(generateTestId("COACH"));
        course.setCourseTime(Instant.now().plus(2, ChronoUnit.HOURS));
        course.setCourseDuration(60);
        course.setCourseCapacity(20);
        course.setCourseAvailable(15);
        course.setCourseStatus(COURSE_STATUS_SCHEDULED);
        course.setGymId(generateTestId("GYM"));
        return course;
    }

    public static Course buildFullCapacityCourse() {
        Course course = new Course();
        course.setCourseId(generateTestId("COURSE"));
        course.setCourseName("高强度HIIT课程");
        course.setCourseType("hiit");
        course.setCourseCoach(generateTestId("COACH"));
        course.setCourseTime(Instant.now().plus(1, ChronoUnit.HOURS));
        course.setCourseDuration(45);
        course.setCourseCapacity(10);
        course.setCourseAvailable(0);
        course.setCourseStatus(COURSE_STATUS_SCHEDULED);
        course.setGymId(generateTestId("GYM"));
        return course;
    }

    public static Course buildCancelledCourse() {
        Course course = new Course();
        course.setCourseId(generateTestId("COURSE"));
        course.setCourseName("已取消课程");
        course.setCourseType("strength");
        course.setCourseCoach(generateTestId("COACH"));
        course.setCourseTime(Instant.now().minus(1, ChronoUnit.HOURS));
        course.setCourseDuration(60);
        course.setCourseCapacity(15);
        course.setCourseAvailable(10);
        course.setCourseStatus(COURSE_STATUS_CANCELLED);
        course.setGymId(generateTestId("GYM"));
        return course;
    }

    public static Course buildAlmostFullCourse() {
        Course course = new Course();
        course.setCourseId(generateTestId("COURSE"));
        course.setCourseName("即将满员课程");
        course.setCourseType("cardio");
        course.setCourseCoach(generateTestId("COACH"));
        course.setCourseTime(Instant.now().plus(3, ChronoUnit.HOURS));
        course.setCourseDuration(45);
        course.setCourseCapacity(10);
        course.setCourseAvailable(1);
        course.setCourseStatus(COURSE_STATUS_SCHEDULED);
        course.setGymId(generateTestId("GYM"));
        return course;
    }

    public static Coach buildAvailableCoach() {
        Coach coach = new Coach();
        coach.setCoachId(generateTestId("COACH"));
        coach.setCoachName("可用教练");
        coach.setCoachType("yoga");
        coach.setCoachRating(4.8);
        coach.setCoachStatus(COACH_STATUS_AVAILABLE);
        coach.setCreatedAt(Instant.now().minus(180, ChronoUnit.DAYS));
        coach.setGymId(generateTestId("GYM"));
        coach.setBookingCount(0);
        return coach;
    }

    public static Coach buildUnavailableCoach() {
        Coach coach = new Coach();
        coach.setCoachId(generateTestId("COACH"));
        coach.setCoachName("不可用教练");
        coach.setCoachType("strength");
        coach.setCoachRating(4.5);
        coach.setCoachStatus(COACH_STATUS_UNAVAILABLE);
        coach.setCreatedAt(Instant.now().minus(120, ChronoUnit.DAYS));
        coach.setGymId(generateTestId("GYM"));
        coach.setBookingCount(25);
        return coach;
    }

    public static Booking buildConfirmedBooking(String memberId, String courseId, String coachId) {
        Booking booking = new Booking();
        booking.setBookingId(generateTestId("BOOKING"));
        booking.setMemberId(memberId);
        booking.setCourseId(courseId);
        booking.setCoachId(coachId);
        booking.setBookingStatus(BOOKING_STATUS_CONFIRMED);
        booking.setBookingTime(Instant.now().minus(1, ChronoUnit.HOURS));
        booking.setConfirmedAt(Instant.now().minus(59, ChronoUnit.MINUTES));
        return booking;
    }

    public static Training buildMediumTraining(String memberId, String courseId) {
        Training training = new Training();
        training.setTrainingId(generateTestId("TRAINING"));
        training.setMemberId(memberId);
        training.setCourseId(courseId);
        training.setTrainingDuration(60);
        training.setTrainingIntensity(TRAINING_INTENSITY_MEDIUM);
        training.setTrainingCalories(300);
        training.setTrainingTime(Instant.now().minus(2, ChronoUnit.HOURS));
        training.setTrainingEffectScore(10.0);
        return training;
    }

    public static Training buildHighIntensityTraining(String memberId, String courseId) {
        Training training = new Training();
        training.setTrainingId(generateTestId("TRAINING"));
        training.setMemberId(memberId);
        training.setCourseId(courseId);
        training.setTrainingDuration(45);
        training.setTrainingIntensity(TRAINING_INTENSITY_HIGH);
        training.setTrainingCalories(450);
        training.setTrainingTime(Instant.now().minus(5, ChronoUnit.HOURS));
        training.setTrainingEffectScore(15.0);
        return training;
    }

    public static Training buildLowIntensityTraining(String memberId, String courseId) {
        Training training = new Training();
        training.setTrainingId(generateTestId("TRAINING"));
        training.setMemberId(memberId);
        training.setCourseId(courseId);
        training.setTrainingDuration(30);
        training.setTrainingIntensity(TRAINING_INTENSITY_LOW);
        training.setTrainingCalories(75);
        training.setTrainingTime(Instant.now().minus(8, ChronoUnit.HOURS));
        training.setTrainingEffectScore(5.0);
        return training;
    }

    public static Plan buildWeightLossPlan(String memberId) {
        Plan plan = new Plan();
        plan.setPlanId(generateTestId("PLAN"));
        plan.setMemberId(memberId);
        plan.setPlanType("weight_loss");
        plan.setPlanDuration(30);
        plan.setPlanTarget("减重5公斤");
        plan.setPlanProgress(50);
        plan.setPlanStatus(PLAN_STATUS_IN_PROGRESS);
        plan.setCreatedAt(Instant.now().minus(15, ChronoUnit.DAYS));
        plan.setPlanContent("健身计划类型: weight_loss\n计划周期: 30天\n训练频率: 每周5-6次\n每次训练时长: 45-60分钟\n包含内容: 有氧运动、力量训练、柔韧性练习");
        return plan;
    }

    public static Plan buildCompletedPlan(String memberId) {
        Plan plan = new Plan();
        plan.setPlanId(generateTestId("PLAN"));
        plan.setMemberId(memberId);
        plan.setPlanType("muscle_building");
        plan.setPlanDuration(60);
        plan.setPlanTarget("增肌3公斤");
        plan.setPlanProgress(100);
        plan.setPlanStatus(PLAN_STATUS_COMPLETED);
        plan.setCreatedAt(Instant.now().minus(90, ChronoUnit.DAYS));
        plan.setPlanContent("健身计划类型: muscle_building\n计划周期: 60天\n训练频率: 每周4-5次\n每次训练时长: 60-90分钟\n包含内容: 力量训练为主，配合有氧运动");
        return plan;
    }

    public static Plan buildNewPlan(String memberId) {
        Plan plan = new Plan();
        plan.setPlanId(generateTestId("PLAN"));
        plan.setMemberId(memberId);
        plan.setPlanType("general");
        plan.setPlanDuration(30);
        plan.setPlanTarget("保持健康");
        plan.setPlanProgress(0);
        plan.setPlanStatus(PLAN_STATUS_IN_PROGRESS);
        plan.setCreatedAt(Instant.now());
        plan.setPlanContent("健身计划类型: general\n计划周期: 30天\n训练频率: 每周5-6次\n每次训练时长: 45-60分钟\n包含内容: 有氧运动、力量训练、柔韧性练习");
        return plan;
    }

    public static Gym buildActiveGym() {
        Gym gym = new Gym();
        gym.setGymId(generateTestId("GYM"));
        gym.setGymName("中心健身馆");
        gym.setGymAddress("北京市朝阳区xxx路xxx号");
        gym.setGymPhone("010-12345678");
        gym.setGymStatus(GYM_STATUS_ACTIVE);
        gym.setOpeningHours("07:00-22:00");
        gym.setCreatedAt(Instant.now().minus(365, ChronoUnit.DAYS));
        return gym;
    }

    public static Equipment buildTreadmill(String gymId) {
        Equipment equipment = new Equipment();
        equipment.setEquipmentId(generateTestId("EQUIPMENT"));
        equipment.setEquipmentName("跑步机");
        equipment.setEquipmentType("cardio");
        equipment.setEquipmentStatus(EQUIPMENT_STATUS_AVAILABLE);
        equipment.setGymId(gymId);
        equipment.setLastMaintenance(Instant.now().minus(30, ChronoUnit.DAYS));
        equipment.setPurchaseDate(Instant.now().minus(180, ChronoUnit.DAYS));
        return equipment;
    }

    public static Equipment buildMaintenanceEquipment(String gymId) {
        Equipment equipment = new Equipment();
        equipment.setEquipmentId(generateTestId("EQUIPMENT"));
        equipment.setEquipmentName("哑铃组");
        equipment.setEquipmentType("strength");
        equipment.setEquipmentStatus(EQUIPMENT_STATUS_MAINTENANCE);
        equipment.setGymId(gymId);
        equipment.setLastMaintenance(Instant.now().minus(90, ChronoUnit.DAYS));
        equipment.setPurchaseDate(Instant.now().minus(365, ChronoUnit.DAYS));
        return equipment;
    }

    public static MemberRequest buildMemberRegistrationRequest() {
        MemberRequest request = new MemberRequest();
        request.setMemberName("新注册会员");
        request.setMemberPhone("13900139001");
        request.setMemberType(MEMBER_TYPE_MONTHLY);
        request.setMemberLevel(REGULAR_MEMBER_LEVEL);
        return request;
    }

    public static MemberRequest buildVipMemberRegistrationRequest() {
        MemberRequest request = new MemberRequest();
        request.setMemberName("VIP新会员");
        request.setMemberPhone("13900139002");
        request.setMemberType(MEMBER_TYPE_ANNUAL);
        request.setMemberLevel(VIP_MEMBER_LEVEL);
        return request;
    }

    public static BookingRequest buildBookingRequest(String memberId, String courseId) {
        BookingRequest request = new BookingRequest();
        request.setMemberId(memberId);
        request.setCourseId(courseId);
        return request;
    }

    public static TrainingRequest buildMediumTrainingRequest(String memberId, String courseId) {
        TrainingRequest request = new TrainingRequest();
        request.setMemberId(memberId);
        request.setCourseId(courseId);
        request.setTrainingDuration(60);
        request.setTrainingIntensity(TRAINING_INTENSITY_MEDIUM);
        return request;
    }

    public static TrainingRequest buildHighIntensityTrainingRequest(String memberId, String courseId) {
        TrainingRequest request = new TrainingRequest();
        request.setMemberId(memberId);
        request.setCourseId(courseId);
        request.setTrainingDuration(45);
        request.setTrainingIntensity(TRAINING_INTENSITY_HIGH);
        return request;
    }

    public static PlanRequest buildWeightLossPlanRequest(String memberId) {
        PlanRequest request = new PlanRequest();
        request.setMemberId(memberId);
        request.setPlanType("weight_loss");
        request.setPlanDuration(30);
        request.setPlanTarget("减重5公斤");
        return request;
    }

    public static PlanRequest buildGeneralPlanRequest(String memberId) {
        PlanRequest request = new PlanRequest();
        request.setMemberId(memberId);
        request.setPlanType("general");
        request.setPlanDuration(30);
        request.setPlanTarget("保持健康");
        return request;
    }

    public static Statistic buildMonthlyStatistic(String month) {
        Statistic statistic = new Statistic();
        statistic.setStatId(generateTestId("STAT"));
        statistic.setStatMonth(month);
        statistic.setMemberCount(100);
        statistic.setBookingCount(500);
        statistic.setTrainingCount(400);
        statistic.setTotalCalories(120000);
        statistic.setPlanCount(50);
        return statistic;
    }

    public static History buildMemberRegistrationHistory(String memberId, String memberData) {
        History history = new History();
        history.setHistoryId(generateTestId("HISTORY"));
        history.setMemberId(memberId);
        history.setActionType("MEMBER_REGISTER");
        history.setActionData(memberData);
        history.setActionTime(Instant.now());
        history.setRelatedId(memberId);
        return history;
    }

    public static History buildBookingHistory(String memberId, String bookingId, String bookingData) {
        History history = new History();
        history.setHistoryId(generateTestId("HISTORY"));
        history.setMemberId(memberId);
        history.setActionType("BOOKING_CREATE");
        history.setActionData(bookingData);
        history.setActionTime(Instant.now());
        history.setRelatedId(bookingId);
        return history;
    }

    public static History buildTrainingHistory(String memberId, String trainingId, String trainingData) {
        History history = new History();
        history.setHistoryId(generateTestId("HISTORY"));
        history.setMemberId(memberId);
        history.setActionType("TRAINING_RECORD");
        history.setActionData(trainingData);
        history.setActionTime(Instant.now());
        history.setRelatedId(trainingId);
        return history;
    }

    public static List<Member> buildMultipleMembers(int count) {
        List<Member> members = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            members.add(buildRegularMember());
        }
        return members;
    }

    public static List<Course> buildMultipleCourses(int count) {
        List<Course> courses = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            courses.add(buildYogaCourse());
        }
        return courses;
    }

    public static List<Training> buildMultipleTrainings(int count, String memberId, String courseId) {
        List<Training> trainings = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            trainings.add(buildMediumTraining(memberId, courseId));
        }
        return trainings;
    }

    public static List<History> buildMultipleHistoryRecords(int count, String memberId) {
        List<History> histories = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            histories.add(buildMemberRegistrationHistory(memberId, "test data " + i));
        }
        return histories;
    }

    public static Member buildMemberWithCustomStats(String memberId, int bookingCount, int trainingCount, int totalCalories) {
        Member member = new Member();
        member.setMemberId(memberId);
        member.setMemberName("统计测试会员");
        member.setMemberPhone("13800138" + String.format("%04d", (int) (Math.random() * 10000)));
        member.setMemberType(MEMBER_TYPE_ANNUAL);
        member.setMemberStatus(ACTIVE_MEMBER_STATUS);
        member.setMemberLevel(REGULAR_MEMBER_LEVEL);
        member.setRegisteredAt(Instant.now().minus(60, ChronoUnit.DAYS));
        member.setExpireAt(Instant.now().plus(300, ChronoUnit.DAYS));
        member.setBookingCount(bookingCount);
        member.setTrainingCount(trainingCount);
        member.setTotalCalories(totalCalories);
        return member;
    }

    public static Member buildMemberWithStatusAndLevel(String status, String level) {
        Member member = new Member();
        member.setMemberId(generateTestId("MEMBER"));
        member.setMemberName("测试会员_" + status + "_" + level);
        member.setMemberPhone("138" + String.format("%08d", (int) (Math.random() * 100000000)));
        member.setMemberType(MEMBER_TYPE_MONTHLY);
        member.setMemberStatus(status);
        member.setMemberLevel(level);
        member.setRegisteredAt(Instant.now().minus(30, ChronoUnit.DAYS));
        member.setExpireAt(Instant.now().plus(30, ChronoUnit.DAYS));
        member.setBookingCount(0);
        member.setTrainingCount(0);
        member.setTotalCalories(0);
        return member;
    }
}
