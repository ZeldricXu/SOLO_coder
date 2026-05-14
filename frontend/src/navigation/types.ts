export type RootTabParamList = {
  Apps: undefined;
  Versions: undefined;
  Feedback: undefined;
  Reports: undefined;
};

export type AppNavigatorParamList = {
  AppManagement: undefined;
  AppForm: { appId?: string };
  AppDetail: { appId: string };
  VersionManagement: undefined;
  VersionForm: { appId: string };
  FeedbackManagement: undefined;
  FeedbackDetail: { feedbackId: string };
};
