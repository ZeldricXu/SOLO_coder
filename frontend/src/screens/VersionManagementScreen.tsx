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
  TextInput,
  Modal,
  Picker,
} from 'react-native';
import { useFocusEffect } from '@react-navigation/native';
import Icon from 'react-native-vector-icons/MaterialIcons';
import type { Version, App } from '../types';
import { versionApi, appApi } from '../services/api';
import { StackNavigationProp } from '@react-navigation/stack';
import { AppNavigatorParamList } from '../navigation/types';

type Props = {
  navigation: StackNavigationProp<AppNavigatorParamList, 'VersionManagement'>;
};

const getStatusColor = (status: string) => {
  switch (status) {
    case 'approved':
      return '#4CAF50';
    case 'pending_approval':
      return '#FF9800';
    case 'rejected':
      return '#F44336';
    default:
      return '#9E9E9E';
  }
};

const getStatusText = (status: string) => {
  switch (status) {
    case 'approved':
      return '已发布';
    case 'pending_approval':
      return '待审批';
    case 'rejected':
      return '已拒绝';
    default:
      return status;
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

export default function VersionManagementScreen({ navigation }: Props) {
  const [versions, setVersions] = useState<Version[]>([]);
  const [apps, setApps] = useState<App[]>([]);
  const [loading, setLoading] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [filterStatus, setFilterStatus] = useState<string | null>(null);
  const [showApproveModal, setShowApproveModal] = useState(false);
  const [selectedVersion, setSelectedVersion] = useState<Version | null>(null);
  const [approveResult, setApproveResult] = useState('approved');
  const [approveComment, setApproveComment] = useState('');
  const [approving, setApproving] = useState(false);

  const loadData = async () => {
    try {
      setLoading(true);
      const [versionsRes, appsRes] = await Promise.all([
        versionApi.getVersions(filterStatus ? { status: filterStatus } : undefined),
        appApi.getApps(),
      ]);
      setVersions(versionsRes.data.data);
      setApps(appsRes.data.data);
    } catch (error) {
      console.error('Failed to load versions:', error);
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
    }, [filterStatus])
  );

  const getAppName = (appId: string) => {
    const app = apps.find(a => a.appId === appId);
    return app?.name || appId;
  };

  const openApproveModal = (version: Version) => {
    if (version.publishStatus !== 'pending_approval') {
      Alert.alert('提示', '该版本状态不允许审批');
      return;
    }
    setSelectedVersion(version);
    setApproveResult('approved');
    setApproveComment('');
    setShowApproveModal(true);
  };

  const handleApprove = async () => {
    if (!selectedVersion) return;
    if (approveResult === 'rejected' && !approveComment.trim()) {
      Alert.alert('提示', '拒绝时请填写原因');
      return;
    }

    try {
      setApproving(true);
      await versionApi.approve({
        versionId: selectedVersion.versionId,
        result: approveResult,
        comment: approveComment,
        approver: 'reviewer_001',
      });
      Alert.alert('成功', approveResult === 'approved' ? '审批通过' : '审批拒绝');
      setShowApproveModal(false);
      loadData();
    } catch (error: any) {
      Alert.alert('审批失败', error.message);
    } finally {
      setApproving(false);
    }
  };

  const renderItem = ({ item }: { item: Version }) => (
    <View style={styles.card}>
      <View style={styles.cardHeader}>
        <View style={styles.iconContainer}>
          <Icon name="system-update-tv" size={32} color="#1976D2" />
        </View>
        <View style={styles.infoContainer}>
          <Text style={styles.appName}>{getAppName(item.appId)}</Text>
          <Text style={styles.versionCode}>
            {item.versionName || `V${item.versionCode}`}
          </Text>
          <Text style={styles.submitInfo}>提交者：{item.submitter || '未知'}</Text>
        </View>
        <View style={[styles.statusBadge, { backgroundColor: getStatusColor(item.publishStatus) }]}>
          <Text style={styles.statusText}>{getStatusText(item.publishStatus)}</Text>
        </View>
      </View>

      {item.releaseNote && (
        <Text style={styles.releaseNote} numberOfLines={3}>
          更新说明：{item.releaseNote}
        </Text>
      )}

      <View style={styles.metaRow}>
        <Text style={styles.metaText}>提交时间：{item.submittedAt}</Text>
        {item.approvedAt && (
          <Text style={styles.metaText}>审批时间：{item.approvedAt}</Text>
        )}
      </View>

      {item.rejectReason && (
        <View style={styles.rejectBox}>
          <Text style={styles.rejectLabel}>拒绝原因：</Text>
          <Text style={styles.rejectText}>{item.rejectReason}</Text>
        </View>
      )}

      <View style={styles.actions}>
        {item.publishStatus === 'pending_approval' && (
          <TouchableOpacity
            style={styles.approveButton}
            onPress={() => openApproveModal(item)}
          >
            <Icon name="check-circle" size={18} color="#4CAF50" />
            <Text style={[styles.actionText, { color: '#4CAF50' }]}>审批</Text>
          </TouchableOpacity>
        )}
        <TouchableOpacity
          style={styles.actionButton}
          onPress={() => {
            if (item.packageUrl) {
              Alert.alert('下载地址', item.packageUrl);
            }
          }}
        >
          <Icon name="cloud-download" size={18} color="#1976D2" />
          <Text style={styles.actionText}>查看包</Text>
        </TouchableOpacity>
      </View>
    </View>
  );

  const renderFilter = () => (
    <View style={styles.filterContainer}>
      <TouchableOpacity
        style={[styles.filterChip, !filterStatus && styles.filterChipActive]}
        onPress={() => setFilterStatus(null)}
      >
        <Text style={[styles.filterChipText, !filterStatus && styles.filterChipTextActive]}>全部</Text>
      </TouchableOpacity>
      <TouchableOpacity
        style={[styles.filterChip, filterStatus === 'pending_approval' && styles.filterChipActive]}
        onPress={() => setFilterStatus('pending_approval')}
      >
        <Text style={[styles.filterChipText, filterStatus === 'pending_approval' && styles.filterChipTextActive]}>待审批</Text>
      </TouchableOpacity>
      <TouchableOpacity
        style={[styles.filterChip, filterStatus === 'approved' && styles.filterChipActive]}
        onPress={() => setFilterStatus('approved')}
      >
        <Text style={[styles.filterChipText, filterStatus === 'approved' && styles.filterChipTextActive]}>已发布</Text>
      </TouchableOpacity>
      <TouchableOpacity
        style={[styles.filterChip, filterStatus === 'rejected' && styles.filterChipActive]}
        onPress={() => setFilterStatus('rejected')}
      >
        <Text style={[styles.filterChipText, filterStatus === 'rejected' && styles.filterChipTextActive]}>已拒绝</Text>
      </TouchableOpacity>
    </View>
  );

  if (loading && versions.length === 0) {
    return (
      <View style={styles.loadingContainer}>
        <ActivityIndicator size="large" color="#1976D2" />
        <Text style={styles.loadingText}>加载中...</Text>
      </View>
    );
  }

  return (
    <View style={styles.container}>
      {renderFilter()}
      
      <FlatList
        data={versions}
        renderItem={renderItem}
        keyExtractor={(item) => item.versionId}
        contentContainerStyle={styles.listContainer}
        refreshControl={
          <RefreshControl refreshing={refreshing} onRefresh={onRefresh} />
        }
        ListEmptyComponent={
          <View style={styles.emptyContainer}>
            <Icon name="update-disabled" size={64} color="#CCC" />
            <Text style={styles.emptyText}>暂无版本记录</Text>
          </View>
        }
      />

      <TouchableOpacity
        style={styles.fab}
        onPress={() => {
          if (apps.length === 0) {
            Alert.alert('提示', '请先创建应用');
            return;
          }
          navigation.navigate('VersionForm', { appId: apps[0].appId });
        }}
      >
        <Icon name="add" size={32} color="#FFF" />
      </TouchableOpacity>

      <Modal
        visible={showApproveModal}
        transparent
        animationType="slide"
        onRequestClose={() => setShowApproveModal(false)}
      >
        <View style={styles.modalOverlay}>
          <View style={styles.modalContent}>
            <Text style={styles.modalTitle}>版本审批</Text>
            
            <Text style={styles.modalSubtitle}>
              {selectedVersion && `${getAppName(selectedVersion.appId)} - ${selectedVersion.versionName || selectedVersion.versionCode}`}
            </Text>

            <View style={styles.modalField}>
              <Text style={styles.label}>审批结果</Text>
              <View style={styles.pickerContainer}>
                <Picker
                  selectedValue={approveResult}
                  onValueChange={(value) => setApproveResult(value)}
                  style={styles.picker}
                >
                  <Picker.Item label="通过" value="approved" />
                  <Picker.Item label="拒绝" value="rejected" />
                </Picker>
              </View>
            </View>

            <View style={styles.modalField}>
              <Text style={styles.label}>审批意见</Text>
              <TextInput
                style={[styles.input, styles.multilineInput]}
                value={approveComment}
                onChangeText={setApproveComment}
                placeholder={approveResult === 'rejected' ? '请输入拒绝原因' : '请输入审批意见（可选）'}
                placeholderTextColor="#9E9E9E"
                multiline
                numberOfLines={3}
                textAlignVertical="top"
              />
            </View>

            <View style={styles.modalActions}>
              <TouchableOpacity
                style={[styles.modalButton, styles.cancelButton]}
                onPress={() => setShowApproveModal(false)}
              >
                <Text style={styles.cancelButtonText}>取消</Text>
              </TouchableOpacity>
              <TouchableOpacity
                style={[styles.modalButton, styles.submitButton, approving && styles.disabledButton]}
                onPress={handleApprove}
                disabled={approving}
              >
                {approving ? (
                  <ActivityIndicator size="small" color="#FFF" />
                ) : (
                  <Text style={styles.submitButtonText}>确认</Text>
                )}
              </TouchableOpacity>
            </View>
          </View>
        </View>
      </Modal>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#F5F5F5',
  },
  filterContainer: {
    flexDirection: 'row',
    padding: 12,
    backgroundColor: '#FFF',
    borderBottomWidth: 1,
    borderBottomColor: '#E0E0E0',
  },
  filterChip: {
    paddingHorizontal: 16,
    paddingVertical: 8,
    borderRadius: 20,
    marginRight: 8,
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
    paddingBottom: 100,
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
    alignItems: 'center',
    marginBottom: 12,
  },
  iconContainer: {
    width: 48,
    height: 48,
    backgroundColor: '#E3F2FD',
    borderRadius: 10,
    justifyContent: 'center',
    alignItems: 'center',
    marginRight: 12,
  },
  infoContainer: {
    flex: 1,
  },
  appName: {
    fontSize: 16,
    fontWeight: '600',
    color: '#212121',
    marginBottom: 2,
  },
  versionCode: {
    fontSize: 14,
    color: '#757575',
    marginBottom: 2,
  },
  submitInfo: {
    fontSize: 12,
    color: '#9E9E9E',
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
  releaseNote: {
    fontSize: 13,
    color: '#616161',
    marginBottom: 8,
    lineHeight: 18,
  },
  metaRow: {
    marginBottom: 8,
  },
  metaText: {
    fontSize: 11,
    color: '#9E9E9E',
    marginBottom: 2,
  },
  rejectBox: {
    backgroundColor: '#FFEBEE',
    padding: 10,
    borderRadius: 6,
    marginBottom: 8,
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
  actions: {
    flexDirection: 'row',
    borderTopWidth: 1,
    borderTopColor: '#F0F0F0',
    paddingTop: 12,
  },
  actionButton: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 6,
    paddingHorizontal: 12,
    marginRight: 16,
  },
  approveButton: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 6,
    paddingHorizontal: 12,
    marginRight: 16,
    borderWidth: 1,
    borderColor: '#4CAF50',
    borderRadius: 6,
  },
  actionText: {
    marginLeft: 4,
    fontSize: 14,
    color: '#1976D2',
  },
  fab: {
    position: 'absolute',
    right: 20,
    bottom: 30,
    width: 56,
    height: 56,
    borderRadius: 28,
    backgroundColor: '#1976D2',
    justifyContent: 'center',
    alignItems: 'center',
    elevation: 4,
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
    marginBottom: 4,
    textAlign: 'center',
  },
  modalSubtitle: {
    fontSize: 14,
    color: '#757575',
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
  pickerContainer: {
    backgroundColor: '#F5F5F5',
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#E0E0E0',
  },
  picker: {
    height: 50,
  },
  input: {
    backgroundColor: '#F5F5F5',
    borderWidth: 1,
    borderColor: '#E0E0E0',
    borderRadius: 8,
    paddingHorizontal: 12,
    paddingVertical: 12,
    fontSize: 16,
  },
  multilineInput: {
    minHeight: 80,
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
