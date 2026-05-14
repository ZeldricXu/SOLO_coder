export interface App {
  appId: string;
  name: string;
  icon?: string;
  description?: string;
  category: string;
  platform: string;
  developerId: string;
  createdAt: string;
  status: string;
  updatedAt?: string;
}

export interface Version {
  versionId: string;
  appId: string;
  versionCode: string;
  versionName?: string;
  packageUrl: string;
  releaseNote?: string;
  publishStatus: string;
  submitter?: string;
  submittedAt: string;
  approvedAt?: string;
  approver?: string;
  rejectReason?: string;
}

export interface Feedback {
  feedbackId: string;
  appId: string;
  userId: string;
  feedbackType: string;
  content: string;
  rating?: number;
  status: string;
  priority: string;
  assignee?: string;
  createdAt: string;
  processedAt?: string;
  processingNote?: string;
  title?: string;
  matchedKeywords?: string[];
}

export interface Statistics {
  statId: string;
  appId: string;
  statDate: string;
  downloadCount: number;
  activeUsers: number;
  avgRating: number;
  feedbackCount: number;
}

export interface Notification {
  notificationId: string;
  recipientId: string;
  type: string;
  title: string;
  content: string;
  relatedType?: string;
  relatedId?: string;
  isRead: boolean;
  createdAt: string;
}

export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
}

export interface ChartData {
  labels: string[];
  downloads: number[];
  activeUsers: number[];
  ratings: number[];
  feedbacks: number[];
  calculatedAt?: string;
}

export interface SummaryStats {
  totalDownloads: number;
  totalActiveUsers: number;
  avgRating: number;
  totalFeedbacks?: number;
  latestDownloads?: number;
  latestActiveUsers?: number;
  latestRating?: number;
  latestDate?: string;
  calculatedAt?: string;
}

export interface AsyncStatsResponse<T> {
  cacheHit: boolean;
  status?: string;
  taskId?: string;
  message?: string;
  retryAfterSeconds?: number;
  data?: T;
}

export interface ClassificationResult {
  feedbackType: string;
  feedbackTypeName: string;
  priority: string;
  priorityName: string;
  assignee: string;
  matchedKeywords: string[];
}

export interface FeedbackStats {
  total: number;
  pending: number;
  processing: number;
  processed: number;
  closed: number;
  byPriority: {
    high: number;
    medium: number;
    low: number;
  };
  byType: {
    bugReport: number;
    featureRequest: number;
    complaint: number;
    question: number;
    other: number;
  };
}

export interface PermissionCheck {
  hasPermission: boolean;
  role: string;
  userId: string;
}
