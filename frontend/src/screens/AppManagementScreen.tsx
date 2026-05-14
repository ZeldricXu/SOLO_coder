import React, { useEffect, useState, useCallback } from 'react';
import {
  View,
  Text,
  FlatList,
  TouchableOpacity,
  StyleSheet,
  Alert,
  RefreshControl,
  ActivityIndicator,
} from 'react-native';
import { useFocusEffect } from '@react-navigation/native';
import Icon from 'react-native-vector-icons/MaterialIcons';
import type { App } from '../types';
import { appApi } from '../services/api';
import { StackNavigationProp } from '@react-navigation/stack';
import { AppNavigatorParamList } from '../navigation/types';

type Props = {
  navigation: StackNavigationProp<AppNavigatorParamList, 'AppManagement'>;
};

const getStatusColor = (status: string) => {
  switch (status) {
    case 'active':
      return '#4CAF50';
    case 'draft':
      return '#FF9800';
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
    case 'draft':
      return '草稿';
    case 'suspended':
      return '已下架';
    default:
      return status;
  }
};

export default function AppManagementScreen({ navigation }: Props) {
  const [apps, setApps] = useState<App[]>([]);
  const [loading, setLoading] = useState(false);
  const [refreshing, setRefreshing] = useState(false);

  const loadApps = async () => {
    try {
      setLoading(true);
      const response = await appApi.getApps();
      setApps(response.data.data);
    } catch (error) {
      console.error('Failed to load apps:', error);
    } finally {
      setLoading(false);
    }
  };

  const onRefresh = async () => {
    setRefreshing(true);
    await loadApps();
    setRefreshing(false);
  };

  useFocusEffect(
    useCallback(() => {
      loadApps();
    }, [])
  );

  const handleDelete = (appId: string, name: string) => {
    Alert.alert(
      '确认删除',
      `确定要删除应用「${name}」吗？此操作不可撤销。`,
      [
        { text: '取消', style: 'cancel' },
        {
          text: '删除',
          style: 'destructive',
          onPress: async () => {
            try {
              await appApi.deleteApp(appId);
              Alert.alert('成功', '应用已删除');
              loadApps();
            } catch (error: any) {
              Alert.alert('删除失败', error.message);
            }
          },
        },
      ]
    );
  };

  const renderItem = ({ item }: { item: App }) => (
    <TouchableOpacity
      style={styles.card}
      onPress={() => navigation.navigate('AppDetail', { appId: item.appId })}
    >
      <View style={styles.cardHeader}>
        <View style={styles.iconContainer}>
          <Icon name="android" size={32} color="#1976D2" />
        </View>
        <View style={styles.infoContainer}>
          <Text style={styles.appName}>{item.name}</Text>
          <Text style={styles.appCategory}>{item.category} · {item.platform}</Text>
        </View>
        <View style={[styles.statusBadge, { backgroundColor: getStatusColor(item.status) }]}>
          <Text style={styles.statusText}>{getStatusText(item.status)}</Text>
        </View>
      </View>
      <Text style={styles.description} numberOfLines={2}>
        {item.description || '暂无描述'}
      </Text>
      <View style={styles.actions}>
        <TouchableOpacity
          style={styles.actionButton}
          onPress={() => navigation.navigate('AppForm', { appId: item.appId })}
        >
          <Icon name="edit" size={18} color="#1976D2" />
          <Text style={styles.actionText}>编辑</Text>
        </TouchableOpacity>
        <TouchableOpacity
          style={styles.actionButton}
          onPress={() => navigation.navigate('VersionForm', { appId: item.appId })}
        >
          <Icon name="cloud-upload" size={18} color="#4CAF50" />
          <Text style={styles.actionText}>发版</Text>
        </TouchableOpacity>
        <TouchableOpacity
          style={[styles.actionButton, styles.destructiveButton]}
          onPress={() => handleDelete(item.appId, item.name)}
        >
          <Icon name="delete" size={18} color="#F44336" />
          <Text style={styles.destructiveText}>删除</Text>
        </TouchableOpacity>
      </View>
    </TouchableOpacity>
  );

  if (loading && apps.length === 0) {
    return (
      <View style={styles.loadingContainer}>
        <ActivityIndicator size="large" color="#1976D2" />
        <Text style={styles.loadingText}>加载中...</Text>
      </View>
    );
  }

  return (
    <View style={styles.container}>
      <FlatList
        data={apps}
        renderItem={renderItem}
        keyExtractor={(item) => item.appId}
        contentContainerStyle={styles.listContainer}
        refreshControl={
          <RefreshControl refreshing={refreshing} onRefresh={onRefresh} />
        }
        ListEmptyComponent={
          <View style={styles.emptyContainer}>
            <Icon name="inbox" size={64} color="#CCC" />
            <Text style={styles.emptyText}>暂无应用</Text>
            <Text style={styles.emptySubtext}>点击下方按钮创建您的第一个应用</Text>
          </View>
        }
      />
      <TouchableOpacity
        style={styles.fab}
        onPress={() => navigation.navigate('AppForm', {})}
      >
        <Icon name="add" size={32} color="#FFF" />
      </TouchableOpacity>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#F5F5F5',
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
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.1,
    shadowRadius: 4,
  },
  cardHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 12,
  },
  iconContainer: {
    width: 56,
    height: 56,
    backgroundColor: '#E3F2FD',
    borderRadius: 12,
    justifyContent: 'center',
    alignItems: 'center',
    marginRight: 12,
  },
  infoContainer: {
    flex: 1,
  },
  appName: {
    fontSize: 18,
    fontWeight: '600',
    color: '#212121',
    marginBottom: 4,
  },
  appCategory: {
    fontSize: 14,
    color: '#757575',
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
  description: {
    fontSize: 14,
    color: '#616161',
    marginBottom: 12,
    lineHeight: 20,
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
    marginRight: 12,
  },
  actionText: {
    marginLeft: 4,
    fontSize: 14,
    color: '#1976D2',
  },
  destructiveButton: {
    marginLeft: 'auto',
    marginRight: 0,
  },
  destructiveText: {
    marginLeft: 4,
    fontSize: 14,
    color: '#F44336',
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
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.3,
    shadowRadius: 8,
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
    fontSize: 18,
    fontWeight: '500',
    color: '#757575',
    marginTop: 12,
  },
  emptySubtext: {
    fontSize: 14,
    color: '#9E9E9E',
    marginTop: 8,
  },
});
