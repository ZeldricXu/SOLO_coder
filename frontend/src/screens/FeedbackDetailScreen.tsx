import React, { useEffect, useState } from 'react';
import {
  View,
  Text,
  ScrollView,
  StyleSheet,
  ActivityIndicator,
  TouchableOpacity,
  Alert,
  TextInput,
  Modal,
  Picker,
} from 'react-native';
import { RouteProp } from '@react-navigation/native';
import { StackNavigationProp } from '@react-navigation/stack';
import Icon from 'react-native-vector-icons/MaterialIcons';
import { AppNavigatorParamList } from '../navigation/types';
import { feedbackApi, appApi } from '../services/api';
import type { Feedback, App } from '../types';

type Props = {
  route: RouteProp<AppNavigatorParamList, 'FeedbackDetail'>;
  navigation: StackNavigationProp<AppNavigatorParamList, 'FeedbackDetail'>;
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

const getPriorityText = (priority: string) => {
  switch (priority) {
    case 'high':
      return '高优先级';
    case 'medium':
      return '中优先级';
    case 'low':
      return '低优先级';
    default:
      return priority;
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

const getTypeText = (type: string) => {
  switch (type) {
    case 'bug_report':
      return 'Bug反馈';
    case 'feature_request':
      return '功能建议';
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
        size={22}
        color={i <= rating ? '#FFC107' : '#E0E0E0'}
      />
    );
  }
  return <View style={styles.ratingContainer}>{stars}</View>;
};

export default function FeedbackDetailScreen({ route, navigation }: Props) {
  const { feedbackId } = route.params;
  const [feedback, setFeedback] = useState<Feedback | null>(null);
  const [app, setApp] = useState<App | null>(null);
  const [loading, setLoading] = useState(true);
  const [showProcessModal, setShowProcessModal] = useState(false);
  const [newStatus, setNewStatus] = useState('processing');
  const [processingNote, setProcessingNote] = useState('');
  const [processing, setProcessing] = useState(false);

  useEffect(() => {
    loadData();
  }, [feedbackId]);

  const loadData = async () => {
    try {
      setLoading(true);
      const fbResponse = await feedbackApi.getFeedback(feedbackId);
      const fb = fbResponse.data.data;
      setFeedback(fb);

      const appResponse = await appApi.getApp(fb.appId);
      setApp(appResponse.data.data);
    } catch (error) {
      console.error('Failed to load feedback detail:', error);
      Alert.alert('加载失败', '无法获取反馈详情');
    } finally {
      setLoading(false);
    }
  };

  const openProcessModal = (targetStatus: string) => {
    setNewStatus(targetStatus);
    setProcessingNote(feedback?.processingNote || '');
    setShowProcessModal(true);
  };

  const handleProcess = async () => {
    try {
      setProcessing(true);
      await feedbackApi.process(feedbackId, {
        status: newStatus,
        processingNote: processingNote || undefined,
      });
      Alert.alert('成功', '反馈状态已更新', [
        { text: '确定', onPress: () => {
          setShowProcessModal(false);
          loadData();
        }},
      ]);
    } catch (error: any) {
      Alert.alert('操作失败', error.message);
    } finally {
      setProcessing(false);
    }
  };

  if (loading) {
    return (
      <View style={styles.loadingContainer}>
        <ActivityIndicator size="large" color="#1976D2" />
      </View>
    );
  }

  if (!feedback) {
    return (
      <View style={styles.errorContainer}>
        <Text style={styles.errorText}>反馈不存在</Text>
      </View>
    );
  }

  return (
    <ScrollView style={styles.container}>
      <View style={styles.headerCard}>
        <View style={styles.headerTop}>
          <View style={[styles.typeBadge, { backgroundColor: getTypeColor(feedback.feedbackType) }]}>
            <Icon name={feedback.feedbackType === 'bug_report' ? 'bug-report' : 'rate-review'} size={18} color="#FFF" />
            <Text style={styles.typeText}>{getTypeText(feedback.feedbackType)}</Text>
          </View>
          <View style={[styles.statusBadge, { backgroundColor: getStatusColor(feedback.status) }]}>
            <Text style={styles.statusText}>{getStatusText(feedback.status)}</Text>
          </View>
        </View>

        <Text style={styles.appName}>{app?.name || feedback.appId}</Text>
        {feedback.rating && renderStars(feedback.rating)}
        
        <View style={styles.metaInfo}>
          <View style={styles.metaItem}>
            <Icon name="person" size={14} color="#9E9E9E" />
            <Text style={styles.metaText}>用户：{feedback.userId}</Text>
          </View>
          <View style={styles.metaItem}>
            <Icon name="flag" size={14} color={getPriorityColor(feedback.priority)} />
            <Text style={[styles.metaText, { color: getPriorityColor(feedback.priority) }]}>
              {getPriorityText(feedback.priority)}
            </Text>
          </View>
          <View style={styles.metaItem}>
            <Icon name="schedule" size={14} color="#9E9E9E" />
            <Text style={styles.metaText}>提交时间：{feedback.createdAt}</Text>
          </View>
          {feedback.assignee && (
            <View style={styles.metaItem}>
              <Icon name="assignment-ind" size={14} color="#1976D2" />
              <Text style={styles.metaText}>处理人：{feedback.assignee}</Text>
            </View>
          )}
          {feedback.processedAt && (
            <View style={styles.metaItem}>
              <Icon name="check-circle" size={14} color="#4CAF50" />
              <Text style={[styles.metaText, { color: '#4CAF50' }]}>处理时间：{feedback.processedAt}</Text>
            </View>
          )}
        </View>
      </View>

      <View style={styles.contentCard}>
        <Text style={styles.sectionTitle}>反馈内容</Text>
        <Text style={styles.content}>{feedback.content}</Text>
      </View>

      {feedback.processingNote && (
        <View style={styles.noteCard}>
          <Text style={styles.sectionTitle}>处理备注</Text>
          <Text style={styles.content}>{feedback.processingNote}</Text>
        </View>
      )}

      {feedback.status !== 'closed' && (
        <View style={styles.actionCard}>
          <Text style={styles.actionTitle}>快捷操作</Text>
          <View style={styles.actionRow}>
            {feedback.status === 'pending' && (
              <TouchableOpacity
                style={[styles.actionButton, styles.actionProcessing]}
                onPress={() => openProcessModal('processing')}
              >
                <Icon name="play-arrow" size={20} color="#2196F3" />
                <Text style={[styles.actionButtonText, { color: '#2196F3' }]}>开始处理</Text>
              </TouchableOpacity>
            )}
            {feedback.status === 'processing' && (
              <TouchableOpacity
                style={[styles.actionButton, styles.actionProcessed]}
                onPress={() => openProcessModal('processed')}
              >
                <Icon name="check" size={20} color="#4CAF50" />
                <Text style={[styles.actionButtonText, { color: '#4CAF50' }]}>标记完成</Text>
              </TouchableOpacity>
            )}
            {(feedback.status === 'processing' || feedback.status === 'processed') && (
              <TouchableOpacity
                style={[styles.actionButton, styles.actionClosed]}
                onPress={() => openProcessModal('closed')}
              >
                <Icon name="close" size={20} color="#757575" />
                <Text style={[styles.actionButtonText, { color: '#757575' }]}>关闭反馈</Text>
              </TouchableOpacity>
            )}
            {feedback.status === 'pending' && (
              <TouchableOpacity
                style={[styles.actionButton, styles.actionClosed]}
                onPress={() => openProcessModal('closed')}
              >
                <Icon name="close" size={20} color="#757575" />
                <Text style={[styles.actionButtonText, { color: '#757575' }]}>直接关闭</Text>
              </TouchableOpacity>
            )}
          </View>
        </View>
      )}

      <Modal
        visible={showProcessModal}
        transparent
        animationType="slide"
        onRequestClose={() => setShowProcessModal(false)}
      >
        <View style={styles.modalOverlay}>
          <View style={styles.modalContent}>
            <Text style={styles.modalTitle}>处理反馈</Text>
            
            <View style={styles.modalField}>
              <Text style={styles.label}>目标状态</Text>
              <View style={styles.statusDisplay}>
                <View style={[styles.statusBadge, { backgroundColor: getStatusColor(newStatus) }]}>
                  <Text style={styles.statusText}>{getStatusText(newStatus)}</Text>
                </View>
              </View>
            </View>

            <View style={styles.modalField}>
              <Text style={styles.label}>处理备注</Text>
              <TextInput
                style={[styles.input, styles.multilineInput]}
                value={processingNote}
                onChangeText={setProcessingNote}
                placeholder="请输入处理备注（可选）"
                placeholderTextColor="#9E9E9E"
                multiline
                numberOfLines={4}
                textAlignVertical="top"
              />
            </View>

            <View style={styles.modalActions}>
              <TouchableOpacity
                style={[styles.modalButton, styles.cancelButton]}
                onPress={() => setShowProcessModal(false)}
              >
                <Text style={styles.cancelButtonText}>取消</Text>
              </TouchableOpacity>
              <TouchableOpacity
                style={[styles.modalButton, styles.submitButton, processing && styles.disabledButton]}
                onPress={handleProcess}
                disabled={processing}
              >
                {processing ? (
                  <ActivityIndicator size="small" color="#FFF" />
                ) : (
                  <Text style={styles.submitButtonText}>确认</Text>
                )}
              </TouchableOpacity>
            </View>
          </View>
        </View>
      </Modal>
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
    marginBottom: 12,
    padding: 16,
    borderRadius: 12,
    elevation: 2,
  },
  headerTop: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 12,
  },
  typeBadge: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 10,
    paddingVertical: 6,
    borderRadius: 20,
  },
  typeText: {
    fontSize: 13,
    fontWeight: '500',
    color: '#FFF',
    marginLeft: 4,
  },
  statusBadge: {
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: 20,
  },
  statusText: {
    fontSize: 13,
    fontWeight: '500',
    color: '#FFF',
  },
  appName: {
    fontSize: 18,
    fontWeight: '600',
    color: '#212121',
    marginBottom: 8,
  },
  ratingContainer: {
    flexDirection: 'row',
    marginBottom: 12,
  },
  metaInfo: {
    backgroundColor: '#FAFAFA',
    borderRadius: 8,
    padding: 12,
  },
  metaItem: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 6,
  },
  metaText: {
    fontSize: 13,
    color: '#757575',
    marginLeft: 6,
  },
  contentCard: {
    backgroundColor: '#FFF',
    marginHorizontal: 16,
    marginBottom: 12,
    padding: 16,
    borderRadius: 12,
    elevation: 2,
  },
  noteCard: {
    backgroundColor: '#E3F2FD',
    marginHorizontal: 16,
    marginBottom: 12,
    padding: 16,
    borderRadius: 12,
    elevation: 2,
  },
  sectionTitle: {
    fontSize: 14,
    fontWeight: '600',
    color: '#424242',
    marginBottom: 8,
  },
  content: {
    fontSize: 15,
    color: '#212121',
    lineHeight: 24,
  },
  actionCard: {
    backgroundColor: '#FFF',
    marginHorizontal: 16,
    marginBottom: 32,
    padding: 16,
    borderRadius: 12,
    elevation: 2,
  },
  actionTitle: {
    fontSize: 14,
    fontWeight: '600',
    color: '#424242',
    marginBottom: 12,
  },
  actionRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 12,
  },
  actionButton: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 12,
    paddingHorizontal: 16,
    borderRadius: 8,
    borderWidth: 1,
  },
  actionProcessing: {
    borderColor: '#2196F3',
    backgroundColor: '#E3F2FD',
  },
  actionProcessed: {
    borderColor: '#4CAF50',
    backgroundColor: '#E8F5E9',
  },
  actionClosed: {
    borderColor: '#BDBDBD',
    backgroundColor: '#F5F5F5',
  },
  actionButtonText: {
    fontSize: 14,
    fontWeight: '500',
    marginLeft: 6,
  },
  modalOverlay: {
    flex: 1,
    backgroundColor: 'rgba(0, 0, 0, 0.5)',
    justifyContent: 'flex-end',
  },
  modalContent: {
    backgroundColor: '#FFF',
    borderTopLeftRadius: 20,
    borderTopRightRadius: 20,
    padding: 20,
  },
  modalTitle: {
    fontSize: 18,
    fontWeight: '600',
    color: '#212121',
    marginBottom: 20,
    textAlign: 'center',
  },
  modalField: {
    marginBottom: 16,
  },
  label: {
    fontSize: 14,
    fontWeight: '500',
    color: '#424242',
    marginBottom: 8,
  },
  statusDisplay: {
    alignItems: 'flex-start',
  },
  input: {
    backgroundColor: '#F5F5F5',
    borderWidth: 1,
    borderColor: '#E0E0E0',
    borderRadius: 8,
    paddingHorizontal: 12,
    paddingVertical: 12,
    fontSize: 16,
    color: '#212121',
  },
  multilineInput: {
    minHeight: 100,
    paddingTop: 12,
  },
  modalActions: {
    flexDirection: 'row',
    marginTop: 20,
  },
  modalButton: {
    flex: 1,
    paddingVertical: 14,
    borderRadius: 8,
    alignItems: 'center',
    marginHorizontal: 6,
  },
  cancelButton: {
    backgroundColor: '#F5F5F5',
  },
  submitButton: {
    backgroundColor: '#1976D2',
  },
  disabledButton: {
    backgroundColor: '#90CAF9',
  },
  cancelButtonText: {
    fontSize: 16,
    color: '#757575',
  },
  submitButtonText: {
    fontSize: 16,
    color: '#FFF',
    fontWeight: '500',
  },
});
