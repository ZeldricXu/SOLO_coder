package com.survey.common;

public class SurveyConstants {

    public static final String SURVEY_STATUS_DRAFT = "draft";
    public static final String SURVEY_STATUS_PUBLISHED = "published";
    public static final String SURVEY_STATUS_CLOSED = "closed";
    public static final String SURVEY_STATUS_EXPIRED = "expired";
    public static final String SURVEY_STATUS_PENDING = "pending";

    public static final String PUBLISH_CHANNEL_EMAIL = "email";
    public static final String PUBLISH_CHANNEL_LINK = "link";
    public static final String PUBLISH_CHANNEL_WECHAT = "wechat";
    public static final String PUBLISH_CHANNEL_SMS = "sms";

    public static final String PUBLISH_RANGE_ALL = "all_users";
    public static final String PUBLISH_RANGE_TARGET = "target_users";
    public static final String PUBLISH_RANGE_DEPARTMENT = "department";

    public static final String PUBLISH_STATUS_PUBLISHED = "published";
    public static final String PUBLISH_STATUS_CANCELLED = "cancelled";
    public static final String PUBLISH_STATUS_PENDING_CONFIRM = "pending_confirm";
    public static final String PUBLISH_STATUS_CONFIRMED = "confirmed";
    public static final String PUBLISH_STATUS_FAILED = "failed";

    public static final String PUBLISH_CONFIRM_PENDING = "pending";
    public static final String PUBLISH_CONFIRM_CONFIRMED = "confirmed";
    public static final String PUBLISH_CONFIRM_FAILED = "failed";

    public static final String ANSWER_REMINDER_PENDING = "pending";
    public static final String ANSWER_REMINDER_SENT = "sent";
    public static final String ANSWER_REMINDER_COMPLETED = "completed";

    public static final int PUBLISH_RETRY_MAX = 3;
    public static final long PUBLISH_RETRY_INTERVAL_MS = 60000;
    public static final int ANSWER_REMINDER_MAX = 3;
    public static final long ANSWER_REMINDER_INTERVAL_MS = 3600000;

    public static final String ANSWER_STATUS_SUBMITTED = "submitted";
    public static final String ANSWER_STATUS_REVIEWING = "reviewing";
    public static final String ANSWER_STATUS_REVIEWED = "reviewed";
    public static final String ANSWER_STATUS_REJECTED = "rejected";

    public static final String REVIEW_STATUS_PENDING = "pending";
    public static final String REVIEW_STATUS_APPROVED = "approved";
    public static final String REVIEW_STATUS_REJECTED = "rejected";

    public static final String QUESTION_TYPE_SINGLE = "single";
    public static final String QUESTION_TYPE_MULTIPLE = "multiple";
    public static final String QUESTION_TYPE_TEXT = "text";
    public static final String QUESTION_TYPE_RATING = "rating";

    public static final String TEMPLATE_STATUS_ACTIVE = "active";
    public static final String TEMPLATE_STATUS_INACTIVE = "inactive";

    public static final String TYPE_STATUS_ACTIVE = "active";
    public static final String TYPE_STATUS_INACTIVE = "inactive";

    public static final String REPORT_STATUS_GENERATED = "generated";

    public static final String BUSINESS_TYPE_SURVEY = "survey";
    public static final String BUSINESS_TYPE_PUBLISH = "publish";
    public static final String BUSINESS_TYPE_ANSWER = "answer";
    public static final String BUSINESS_TYPE_REVIEW = "review";
    public static final String BUSINESS_TYPE_STAT = "stat";
    public static final String BUSINESS_TYPE_REPORT = "report";
}
