import React, { useEffect, useState } from 'react';
import {
  View,
  Text,
  ScrollView,
  StyleSheet,
  ActivityIndicator,
  TouchableOpacity,
  Alert,
} from 'react-native';
import { RouteProp } from '@react-navigation/native';
import { StackNavigationProp } from '@react-navigation/stack';
import Icon from 'react-native-vector-icons/MaterialIcons';
import { AppNavigatorParamList } from '../navigation/types';
import { appApi, versionApi, feedbackApi } from '../services/api';
import type { App, Version } from '../types';

type Props = {
  route: RouteProp<AppNavigatorParamList, 'AppDetail'>;
  navigation: StackNavigationProp<AppNavigatorParamList, 'AppDetail'>;
};

const getStatusColor = (status: string) => {
  switch (status) {
    case 'active':
      return '#4CAF50';
    case 'approved':
      return '#4CAF50';
    case 'pending_approval':
      return '#FF9800';
    case 'draft':
      return '#9E9E9E';
    case 'rejected':
      return '#F44336';
    case 'suspended':
      return '#F44336';
    default:
      return '#9E9E9E';
  }
};

const getStatusText = (status: string) => {
  switch (status) {
    case 'active':
      return '已上线';
    case 'approved':
      return '已发布';
    case 'pending_approval':
      return '待审批';
    case 'draft':
      return '草稿';
    case 'rejected':
      return '已拒绝';
    case 'suspended':
      return '已下架';
    default:
      return status;
  }
};

const getFeedbackStatusColor = (status: string) => {
  switch (status) {
    case 'pending':
      return '#FF9800';
    case 'processing':
      return '#2196F3';
    case 'processed':
      return '#4CAF50';
    case 'closed':
      return '#9E9E9E';
    default:
      return '#9E9E9E';
  }
};

const getFeedbackStatusText = (status: string) => {
  switch (status) {
    case 'pending':
      return '待处理';
    case 'processing':
      return '处理中';
    case 'processed':
      return '已处理';
    case 'closed':
      return '已关闭';
    default:
      return status;
  }
};

export default function AppDetailScreen({ route, navigation }: Props) {
  const { appId } = route.params;
  const [app, setApp] = useState<App | null>(null);
  const [versions, setVersions] = useState<Version[]>([]);
  const [feedbackStats, setFeedbackStats] = useState<any>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    loadData();
  }, [appId]);

  const loadData = async () => {
    try {
      setLoading(true);
      const [appRes, versionsRes, feedbackStatsRes] = await Promise.all([
        appApi.getApp(appId),
        versionApi.getVersions({ appId }),
        feedbackApi.getStats(appId),
      ]);
      setApp(appRes.data.data);
      setVersions(versionsRes.data.data);
      setFeedbackStats(feedbackStatsRes.data.data);
    } catch (error) {
      console.error('Failed to load app detail:', error);
      Alert.alert('加载失败', '无法获取应用详情');
    } finally {
      setLoading(false);
    }
  };

  if (loading) {
    return (
      <View style={styles.loadingContainer}>
        <ActivityIndicator size="large" color="#1976D2" />
      </View>
    );
  }

  if (!app) {
    return (
      <View style={styles.errorContainer}>
        <Text style={styles.errorText}>应用不存在</Text>
      </View>
    );
  }

  return (
    <ScrollView style={styles.container}>
      <View style={styles.headerCard}>
        <View style={styles.headerTop}>
          <View style={styles.iconContainer}>
            <Icon name="android" size={48} color="#1976D2" />
          </View>
          <View style={styles.headerInfo}>
            <Text style={styles.appName}>{app.name}</Text>
            <Text style={styles.appMeta}>{app.category} · {app.platform}</Text>
            <View style={[styles.statusBadge, { backgroundColor: getStatusColor(app.status) }]}>
              <Text style={styles.statusText}>{getStatusText(app.status)}</Text>
            </View>
          </View>
        </View>
        {app.description && (
          <Text style={styles.description}>{app.description}</Text>
        )}
        <View style={styles.headerActions}>
          <TouchableOpacity
            style={styles.actionButton}
            onPress={() => navigation.navigate('AppForm', { appId: app.appId })}
          >
            <Icon name="edit" size={18} color="#1976D2" />
            <Text style={styles.actionText}>编辑</Text>
          </TouchableOpacity>
          <TouchableOpacity
            style={styles.actionButton}
            onPress={() => navigation.navigate('VersionForm', { appId: app.appId })}
          >
            <Icon name="cloud-upload" size={18} color="#4CAF50" />
            <Text style={styles.actionText}>发版</Text>
          </TouchableOpacity>
        </View>
      </View>

      <View style={styles.statsSection}>
        <Text style={styles.sectionTitle}>反馈统计</Text>
        <View style={styles.statsGrid}>
          <View style={styles.statItem}>
            <Text style={styles.statValue}>{feedbackStats?.total || 0}</Text>
            <Text style={styles.statLabel}>总反馈</Text>
          </View>
          <View style={styles.statItem}>
            <Text style={[styles.statValue, { color: getFeedbackStatusColor('pending') }]}>
              {feedbackStats?.pending || 0}
            </Text>
            <Text style={styles.statLabel}>待处理</Text>
          </View>
          <View style={styles.statItem}>
            <Text style={[styles.statValue, { color: getFeedbackStatusColor('processing') }]}>
              {feedbackStats?.processing || 0}
            </Text>
            <Text style={styles.statLabel}>处理中</Text>
          </View>
          <View style={styles.statItem}>
            <Text style={[styles.statValue, { color: getFeedbackStatusColor('processed') }]}>
              {feedbackStats?.processed || 0}
            </Text>
            <Text style={styles.statLabel}>已处理</Text>
          </View>
        </View>
      </View>

      <View style={styles.versionsSection}>
        <Text style={styles.sectionTitle}>版本历史</Text>
        {versions.length === 0 ? (
          <View style={styles.emptyCard}>
            <Icon name="update" size={40} color="#CCC" />
            <Text style={styles.emptyText}>暂无版本记录</Text>
          </View>
        ) : (
          versions.map((version) => (
            <View key={version.versionId} style={styles.versionCard}>
              <View style={styles.versionHeader}>
                <View>
                  <Text style={styles.versionName}>
                    {version.versionName || `V${version.versionCode}`}
                  </Text>
                  <Text style={styles.versionCode}>版本 {version.versionCode}</Text>
                </View>
                <View style={[styles.statusBadge, { backgroundColor: getStatusColor(version.publishStatus) }]}>
                  <Text style={styles.statusText}>{getStatusText(version.publishStatus)}</Text>
                </View>
              </View>
              {version.releaseNote && (
                <Text style={styles.releaseNote} numberOfLines={3}>
                  {version.releaseNote}
                </Text>
              )}
              <Text style={styles.versionTime}>提交时间：{version.submittedAt}</Text>
              {version.approver && (
                <Text style={styles.approver}>审批人：{version.approver}</Text>
              )}
              {version.rejectReason && (
                <View style={styles.rejectReason}>
                  <Text style={styles.rejectLabel}>拒绝原因：</Text>
                  <Text style={styles.rejectText}>{version.rejectReason}</Text>
                </View>
              )}
            </View>
          ))
        )}
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#F5F5F5',
  },
  loadingContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
  },
  errorContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
  },
  errorText: {
    fontSize: 16,
    color: '#757575',
  },
  headerCard: {
    backgroundColor: '#FFF',
    margin: 16,
    padding: 16,
    borderRadius: 12,
    elevation: 2,
  },
  headerTop: {
    flexDirection: 'row',
    marginBottom: 12,
  },
  iconContainer: {
    width: 72,
    height: 72,
    backgroundColor: '#E3F2FD',
    borderRadius: 16,
    justifyContent: 'center',
    alignItems: 'center',
    marginRight: 16,
  },
  headerInfo: {
    flex: 1,
  },
  appName: {
    fontSize: 20,
    fontWeight: '600',
    color: '#212121',
    marginBottom: 4,
  },
  appMeta: {
    fontSize: 14,
    color: '#757575',
    marginBottom: 8,
  },
  statusBadge: {
    alignSelf: 'flex-start',
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 12,
  },
  statusText: {
    fontSize: 12,
    fontWeight: '500',
    color: '#FFF',
  },
  description: {
    fontSize: 14,
    color: '#616161',
    lineHeight: 22,
    marginBottom: 12,
  },
  headerActions: {
    flexDirection: 'row',
    borderTopWidth: 1,
    borderTopColor: '#F0F0F0',
    paddingTop: 12,
  },
  actionButton: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 8,
    paddingHorizontal: 16,
    marginRight: 16,
  },
  actionText: {
    marginLeft: 6,
    fontSize: 14,
    color: '#1976D2',
  },
  statsSection: {
    backgroundColor: '#FFF',
    marginHorizontal: 16,
    marginBottom: 16,
    padding: 16,
    borderRadius: 12,
    elevation: 2,
  },
  sectionTitle: {
    fontSize: 16,
    fontWeight: '600',
    color: '#212121',
    marginBottom: 16,
  },
  statsGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
  },
  statItem: {
    width: '25%',
    alignItems: 'center',
  },
  statValue: {
    fontSize: 24,
    fontWeight: '600',
    color: '#212121',
  },
  statLabel: {
    fontSize: 12,
    color: '#757575',
    marginTop: 4,
  },
  versionsSection: {
    backgroundColor: '#FFF',
    marginHorizontal: 16,
    marginBottom: 32,
    padding: 16,
    borderRadius: 12,
    elevation: 2,
  },
  emptyCard: {
    alignItems: 'center',
    paddingVertical: 30,
  },
  emptyText: {
    marginTop: 8,
    fontSize: 14,
    color: '#9E9E9E',
  },
  versionCard: {
    borderWidth: 1,
    borderColor: '#E0E0E0',
    borderRadius: 8,
    padding: 12,
    marginBottom: 12,
  },
  versionHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-start',
    marginBottom: 8,
  },
  versionName: {
    fontSize: 16,
    fontWeight: '500',
    color: '#212121',
  },
  versionCode: {
    fontSize: 12,
    color: '#757575',
    marginTop: 2,
  },
  releaseNote: {
    fontSize: 13,
    color: '#616161',
    lineHeight: 18,
    marginBottom: 6,
  },
  versionTime: {
    fontSize: 12,
    color: '#9E9E9E',
  },
  approver: {
    fontSize: 12,
    color: '#9E9E9E',
    marginTop: 2,
  },
  rejectReason: {
    marginTop: 8,
    padding: 8,
    backgroundColor: '#FFEBEE',
    borderRadius: 6,
  },
  rejectLabel: {
    fontSize: 12,
    fontWeight: '500',
    color: '#D32F2F',
  },
  rejectText: {
    fontSize: 12,
    color: '#D32F2F',
    marginTop: 2,
  },
});
