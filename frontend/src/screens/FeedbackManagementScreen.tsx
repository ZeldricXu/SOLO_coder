import React, { useEffect, useState, useCallback } from 'react';
import {
  View,
  Text,
  FlatList,
  TouchableOpacity,
  StyleSheet,
  RefreshControl,
  ActivityIndicator,
  Alert,
} from 'react-native';
import { useFocusEffect } from '@react-navigation/native';
import Icon from 'react-native-vector-icons/MaterialIcons';
import type { Feedback, App } from '../types';
import { feedbackApi, appApi } from '../services/api';
import { StackNavigationProp } from '@react-navigation/stack';
import { AppNavigatorParamList } from '../navigation/types';

type Props = {
  navigation: StackNavigationProp<AppNavigatorParamList, 'FeedbackManagement'>;
};

const getStatusColor = (status: string) => {
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

const getStatusText = (status: string) => {
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

const getPriorityColor = (priority: string) => {
  switch (priority) {
    case 'high':
      return '#F44336';
    case 'medium':
      return '#FF9800';
    case 'low':
      return '#4CAF50';
    default:
      return '#9E9E9E';
  }
};

const getPriorityText = (priority: string) => {
  switch (priority) {
    case 'high':
      return '高';
    case 'medium':
      return '中';
    case 'low':
      return '低';
    default:
      return priority;
  }
};

const getTypeText = (type: string) => {
  switch (type) {
    case 'bug_report':
      return 'Bug';
    case 'feature_request':
      return '功能';
    case 'complaint':
      return '投诉';
    default:
      return '其他';
  }
};

const getTypeColor = (type: string) => {
  switch (type) {
    case 'bug_report':
      return '#F44336';
    case 'feature_request':
      return '#2196F3';
    case 'complaint':
      return '#FF9800';
    default:
      return '#9E9E9E';
  }
};

const renderStars = (rating?: number) => {
  if (!rating) return null;
  const stars = [];
  for (let i = 1; i <= 5; i++) {
    stars.push(
      <Icon
        key={i}
        name={i <= rating ? 'star' : 'star-border'}
        size={14}
        color={i <= rating ? '#FFC107' : '#E0E0E0'}
      />
    );
  }
  return <View style={styles.ratingContainer}>{stars}</View>;
};

export default function FeedbackManagementScreen({ navigation }: Props) {
  const [feedbacks, setFeedbacks] = useState<Feedback[]>([]);
  const [apps, setApps] = useState<App[]>([]);
  const [loading, setLoading] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [filterStatus, setFilterStatus] = useState<string | null>(null);
  const [filterPriority, setFilterPriority] = useState<string | null>(null);

  const loadData = async () => {
    try {
      setLoading(true);
      const params: { status?: string; priority?: string } = {};
      if (filterStatus) params.status = filterStatus;
      if (filterPriority) params.priority = filterPriority;
      
      const [feedbacksRes, appsRes] = await Promise.all([
        feedbackApi.getFeedbacks(params),
        appApi.getApps(),
      ]);
      setFeedbacks(feedbacksRes.data.data);
      setApps(appsRes.data.data);
    } catch (error) {
      console.error('Failed to load feedbacks:', error);
    } finally {
      setLoading(false);
    }
  };

  const onRefresh = async () => {
    setRefreshing(true);
    await loadData();
    setRefreshing(false);
  };

  useFocusEffect(
    useCallback(() => {
      loadData();
    }, [filterStatus, filterPriority])
  );

  const getAppName = (appId: string) => {
    const app = apps.find(a => a.appId === appId);
    return app?.name || appId;
  };

  const quickUpdateStatus = async (feedbackId: string, newStatus: string) => {
    try {
      await feedbackApi.process(feedbackId, { status: newStatus });
      Alert.alert('成功', '状态已更新');
      loadData();
    } catch (error: any) {
      Alert.alert('操作失败', error.message);
    }
  };

  const renderItem = ({ item }: { item: Feedback }) => (
    <TouchableOpacity
      style={styles.card}
      onPress={() => navigation.navigate('FeedbackDetail', { feedbackId: item.feedbackId })}
    >
      <View style={styles.cardHeader}>
        <View style={styles.headerLeft}>
          <View style={[styles.typeBadge, { backgroundColor: getTypeColor(item.feedbackType) }]}>
            <Text style={styles.typeText}>{getTypeText(item.feedbackType)}</Text>
          </View>
          <Text style={styles.appName}>{getAppName(item.appId)}</Text>
          {renderStars(item.rating)}
        </View>
        <View style={[styles.statusBadge, { backgroundColor: getStatusColor(item.status) }]}>
          <Text style={styles.statusText}>{getStatusText(item.status)}</Text>
        </View>
      </View>

      <Text style={styles.content} numberOfLines={2}>
        {item.content}
      </Text>

      <View style={styles.metaRow}>
        <View style={[styles.priorityBadge, { backgroundColor: getPriorityColor(item.priority) }]}>
          <Text style={styles.priorityText}>优先级：{getPriorityText(item.priority)}</Text>
        </View>
        <Text style={styles.timeText}>{item.createdAt}</Text>
      </View>

      {item.status !== 'closed' && (
        <View style={styles.quickActions}>
          {item.status === 'pending' && (
            <TouchableOpacity
              style={[styles.quickAction, styles.actionProcessing]}
              onPress={() => quickUpdateStatus(item.feedbackId, 'processing')}
            >
              <Text style={[styles.quickActionText, { color: '#2196F3' }]}>开始处理</Text>
            </TouchableOpacity>
          )}
          {item.status === 'processing' && (
            <TouchableOpacity
              style={[styles.quickAction, styles.actionProcessed]}
              onPress={() => quickUpdateStatus(item.feedbackId, 'processed')}
            >
              <Text style={[styles.quickActionText, { color: '#4CAF50' }]}>标记完成</Text>
            </TouchableOpacity>
          )}
          {item.status === 'processed' && (
            <TouchableOpacity
              style={[styles.quickAction, styles.actionClosed]}
              onPress={() => quickUpdateStatus(item.feedbackId, 'closed')}
            >
              <Text style={[styles.quickActionText, { color: '#757575' }]}>关闭</Text>
            </TouchableOpacity>
          )}
        </View>
      )}
    </TouchableOpacity>
  );

  const renderStatusFilters = () => (
    <View style={styles.filterSection}>
      <Text style={styles.filterLabel}>状态</Text>
      <View style={styles.filterRow}>
        <TouchableOpacity
          style={[styles.filterChip, !filterStatus && styles.filterChipActive]}
          onPress={() => setFilterStatus(null)}
        >
          <Text style={[styles.filterChipText, !filterStatus && styles.filterChipTextActive]}>全部</Text>
        </TouchableOpacity>
        <TouchableOpacity
          style={[styles.filterChip, filterStatus === 'pending' && styles.filterChipActive]}
          onPress={() => setFilterStatus('pending')}
        >
          <Text style={[styles.filterChipText, filterStatus === 'pending' && styles.filterChipTextActive]}>待处理</Text>
        </TouchableOpacity>
        <TouchableOpacity
          style={[styles.filterChip, filterStatus === 'processing' && styles.filterChipActive]}
          onPress={() => setFilterStatus('processing')}
        >
          <Text style={[styles.filterChipText, filterStatus === 'processing' && styles.filterChipTextActive]}>处理中</Text>
        </TouchableOpacity>
        <TouchableOpacity
          style={[styles.filterChip, filterStatus === 'processed' && styles.filterChipActive]}
          onPress={() => setFilterStatus('processed')}
        >
          <Text style={[styles.filterChipText, filterStatus === 'processed' && styles.filterChipTextActive]}>已处理</Text>
        </TouchableOpacity>
      </View>
    </View>
  );

  const renderPriorityFilters = () => (
    <View style={[styles.filterSection, styles.prioritySection]}>
      <Text style={styles.filterLabel}>优先级</Text>
      <View style={styles.filterRow}>
        <TouchableOpacity
          style={[styles.filterChip, !filterPriority && styles.filterChipActive]}
          onPress={() => setFilterPriority(null)}
        >
          <Text style={[styles.filterChipText, !filterPriority && styles.filterChipTextActive]}>全部</Text>
        </TouchableOpacity>
        <TouchableOpacity
          style={[styles.filterChip, filterPriority === 'high' && styles.filterChipActive]}
          onPress={() => setFilterPriority('high')}
        >
          <Text style={[styles.filterChipText, filterPriority === 'high' && styles.filterChipTextActive]}>高</Text>
        </TouchableOpacity>
        <TouchableOpacity
          style={[styles.filterChip, filterPriority === 'medium' && styles.filterChipActive]}
          onPress={() => setFilterPriority('medium')}
        >
          <Text style={[styles.filterChipText, filterPriority === 'medium' && styles.filterChipTextActive]}>中</Text>
        </TouchableOpacity>
        <TouchableOpacity
          style={[styles.filterChip, filterPriority === 'low' && styles.filterChipActive]}
          onPress={() => setFilterPriority('low')}
        >
          <Text style={[styles.filterChipText, filterPriority === 'low' && styles.filterChipTextActive]}>低</Text>
        </TouchableOpacity>
      </View>
    </View>
  );

  if (loading && feedbacks.length === 0) {
    return (
      <View style={styles.loadingContainer}>
        <ActivityIndicator size="large" color="#1976D2" />
        <Text style={styles.loadingText}>加载中...</Text>
      </View>
    );
  }

  return (
    <View style={styles.container}>
      {renderStatusFilters()}
      {renderPriorityFilters()}
      
      <FlatList
        data={feedbacks}
        renderItem={renderItem}
        keyExtractor={(item) => item.feedbackId}
        contentContainerStyle={styles.listContainer}
        refreshControl={
          <RefreshControl refreshing={refreshing} onRefresh={onRefresh} />
        }
        ListEmptyComponent={
          <View style={styles.emptyContainer}>
            <Icon name="feedback" size={64} color="#CCC" />
            <Text style={styles.emptyText}>暂无反馈记录</Text>
          </View>
        }
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#F5F5F5',
  },
  filterSection: {
    backgroundColor: '#FFF',
    paddingVertical: 10,
    paddingHorizontal: 16,
    borderBottomWidth: 1,
    borderBottomColor: '#E0E0E0',
  },
  prioritySection: {
    borderTopWidth: 0,
  },
  filterLabel: {
    fontSize: 12,
    color: '#9E9E9E',
    marginBottom: 6,
  },
  filterRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
  },
  filterChip: {
    paddingHorizontal: 14,
    paddingVertical: 6,
    borderRadius: 16,
    marginRight: 8,
    marginBottom: 4,
    backgroundColor: '#F0F0F0',
  },
  filterChipActive: {
    backgroundColor: '#1976D2',
  },
  filterChipText: {
    fontSize: 13,
    color: '#757575',
  },
  filterChipTextActive: {
    color: '#FFF',
    fontWeight: '500',
  },
  listContainer: {
    padding: 16,
    paddingBottom: 32,
  },
  card: {
    backgroundColor: '#FFF',
    borderRadius: 12,
    padding: 16,
    marginBottom: 12,
    elevation: 2,
  },
  cardHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-start',
    marginBottom: 10,
  },
  headerLeft: {
    flex: 1,
  },
  typeBadge: {
    alignSelf: 'flex-start',
    paddingHorizontal: 8,
    paddingVertical: 2,
    borderRadius: 4,
    marginBottom: 6,
  },
  typeText: {
    fontSize: 11,
    fontWeight: '500',
    color: '#FFF',
  },
  appName: {
    fontSize: 15,
    fontWeight: '600',
    color: '#212121',
    marginBottom: 4,
  },
  ratingContainer: {
    flexDirection: 'row',
  },
  statusBadge: {
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 12,
  },
  statusText: {
    fontSize: 12,
    fontWeight: '500',
    color: '#FFF',
  },
  content: {
    fontSize: 14,
    color: '#424242',
    lineHeight: 20,
    marginBottom: 10,
  },
  metaRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 10,
  },
  priorityBadge: {
    paddingHorizontal: 8,
    paddingVertical: 2,
    borderRadius: 4,
  },
  priorityText: {
    fontSize: 11,
    color: '#FFF',
    fontWeight: '500',
  },
  timeText: {
    fontSize: 12,
    color: '#9E9E9E',
  },
  quickActions: {
    flexDirection: 'row',
    justifyContent: 'flex-end',
    borderTopWidth: 1,
    borderTopColor: '#F0F0F0',
    paddingTop: 10,
  },
  quickAction: {
    paddingVertical: 6,
    paddingHorizontal: 12,
    borderRadius: 6,
  },
  actionProcessing: {
    backgroundColor: '#E3F2FD',
  },
  actionProcessed: {
    backgroundColor: '#E8F5E9',
  },
  actionClosed: {
    backgroundColor: '#F5F5F5',
  },
  quickActionText: {
    fontSize: 13,
    fontWeight: '500',
  },
  loadingContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
  },
  loadingText: {
    marginTop: 12,
    fontSize: 14,
    color: '#757575',
  },
  emptyContainer: {
    alignItems: 'center',
    paddingVertical: 60,
  },
  emptyText: {
    fontSize: 16,
    color: '#9E9E9E',
    marginTop: 12,
  },
});
