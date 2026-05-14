import React, { useEffect, useState } from 'react';
import {
  View,
  Text,
  TextInput,
  ScrollView,
  TouchableOpacity,
  StyleSheet,
  Alert,
  ActivityIndicator,
  Picker,
} from 'react-native';
import { RouteProp } from '@react-navigation/native';
import { StackNavigationProp } from '@react-navigation/stack';
import { AppNavigatorParamList } from '../navigation/types';
import { versionApi, appApi } from '../services/api';
import type { App } from '../types';

type Props = {
  route: RouteProp<AppNavigatorParamList, 'VersionForm'>;
  navigation: StackNavigationProp<AppNavigatorParamList, 'VersionForm'>;
};

export default function VersionFormScreen({ route, navigation }: Props) {
  const { appId: initialAppId } = route.params;
  
  const [apps, setApps] = useState<App[]>([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [form, setForm] = useState({
    appId: initialAppId || '',
    versionCode: '1.0.0',
    versionName: '',
    packageUrl: '',
    releaseNote: '',
  });

  useEffect(() => {
    loadApps();
  }, []);

  const loadApps = async () => {
    try {
      setLoading(true);
      const response = await appApi.getApps();
      setApps(response.data.data);
      if (!initialAppId && response.data.data.length > 0) {
        setForm(prev => ({ ...prev, appId: response.data.data[0].appId }));
      }
    } catch (error) {
      console.error('Failed to load apps:', error);
    } finally {
      setLoading(false);
    }
  };

  const validateForm = () => {
    if (!form.appId) {
      Alert.alert('验证失败', '请选择应用');
      return false;
    }
    if (!form.versionCode.trim()) {
      Alert.alert('验证失败', '版本号不能为空');
      return false;
    }
    if (!form.packageUrl.trim()) {
      Alert.alert('验证失败', '包地址不能为空');
      return false;
    }
    return true;
  };

  const handleSubmit = async () => {
    if (!validateForm()) return;

    try {
      setSaving(true);
      await versionApi.publish({
        appId: form.appId,
        versionCode: form.versionCode,
        versionName: form.versionName || undefined,
        packageUrl: form.packageUrl,
        releaseNote: form.releaseNote || undefined,
        submitter: 'dev_001',
      });
      Alert.alert('成功', '版本已提交审批', [
        { text: '确定', onPress: () => navigation.goBack() },
      ]);
    } catch (error: any) {
      Alert.alert('提交失败', error.response?.data?.message || error.message);
    } finally {
      setSaving(false);
    }
  };

  if (loading && apps.length === 0) {
    return (
      <View style={styles.loadingContainer}>
        <ActivityIndicator size="large" color="#1976D2" />
      </View>
    );
  }

  if (apps.length === 0) {
    return (
      <View style={styles.emptyContainer}>
        <Text style={styles.emptyText}>暂无应用</Text>
        <Text style={styles.emptySubtext}>请先在应用管理中创建应用</Text>
      </View>
    );
  }

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.contentContainer}>
      <View style={styles.formGroup}>
        <Text style={styles.label}>选择应用 *</Text>
        <View style={styles.pickerContainer}>
          <Picker
            selectedValue={form.appId}
            onValueChange={(value) => setForm({ ...form, appId: value })}
            style={styles.picker}
            enabled={!initialAppId}
          >
            {apps.map((app) => (
              <Picker.Item key={app.appId} label={`${app.name} (${app.platform})`} value={app.appId} />
            ))}
          </Picker>
        </View>
      </View>

      <View style={styles.formGroup}>
        <Text style={styles.label}>版本号 *</Text>
        <TextInput
          style={styles.input}
          value={form.versionCode}
          onChangeText={(text) => setForm({ ...form, versionCode: text })}
          placeholder="例如：1.0.0"
          placeholderTextColor="#9E9E9E"
        />
        <Text style={styles.hint}>格式：主版本.次版本.修订号，如 2.0.0</Text>
      </View>

      <View style={styles.formGroup}>
        <Text style={styles.label}>版本名称</Text>
        <TextInput
          style={styles.input}
          value={form.versionName}
          onChangeText={(text) => setForm({ ...form, versionName: text })}
          placeholder="例如：V2.0正式版（可选）"
          placeholderTextColor="#9E9E9E"
        />
      </View>

      <View style={styles.formGroup}>
        <Text style={styles.label}>安装包地址 *</Text>
        <TextInput
          style={styles.input}
          value={form.packageUrl}
          onChangeText={(text) => setForm({ ...form, packageUrl: text })}
          placeholder="请输入APK/IPA下载地址"
          placeholderTextColor="#9E9E9E"
        />
      </View>

      <View style={styles.formGroup}>
        <Text style={styles.label}>更新说明</Text>
        <TextInput
          style={[styles.input, styles.multilineInput]}
          value={form.releaseNote}
          onChangeText={(text) => setForm({ ...form, releaseNote: text })}
          placeholder="请输入本次版本的更新说明（可选）"
          placeholderTextColor="#9E9E9E"
          multiline
          numberOfLines={5}
          textAlignVertical="top"
        />
      </View>

      <View style={styles.noticeBox}>
        <Text style={styles.noticeTitle}>提示</Text>
        <Text style={styles.noticeText}>• 提交后版本将进入待审批状态</Text>
        <Text style={styles.noticeText}>• 审批通过后应用将正式发布</Text>
        <Text style={styles.noticeText}>• 审批拒绝将收到拒绝原因通知</Text>
      </View>

      <TouchableOpacity
        style={[styles.submitButton, saving && styles.disabledButton]}
        onPress={handleSubmit}
        disabled={saving}
      >
        {saving ? (
          <ActivityIndicator size="small" color="#FFF" />
        ) : (
          <Text style={styles.submitButtonText}>提交发布</Text>
        )}
      </TouchableOpacity>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#F5F5F5',
  },
  contentContainer: {
    padding: 16,
  },
  formGroup: {
    marginBottom: 20,
  },
  label: {
    fontSize: 14,
    fontWeight: '500',
    color: '#424242',
    marginBottom: 8,
  },
  input: {
    backgroundColor: '#FFF',
    borderWidth: 1,
    borderColor: '#E0E0E0',
    borderRadius: 8,
    paddingHorizontal: 12,
    paddingVertical: 12,
    fontSize: 16,
    color: '#212121',
  },
  multilineInput: {
    minHeight: 120,
    paddingTop: 12,
  },
  hint: {
    fontSize: 12,
    color: '#9E9E9E',
    marginTop: 4,
  },
  pickerContainer: {
    backgroundColor: '#FFF',
    borderWidth: 1,
    borderColor: '#E0E0E0',
    borderRadius: 8,
  },
  picker: {
    height: 50,
  },
  noticeBox: {
    backgroundColor: '#FFF3E0',
    borderRadius: 8,
    padding: 14,
    marginBottom: 20,
  },
  noticeTitle: {
    fontSize: 14,
    fontWeight: '600',
    color: '#E65100',
    marginBottom: 8,
  },
  noticeText: {
    fontSize: 13,
    color: '#EF6C00',
    lineHeight: 20,
  },
  submitButton: {
    backgroundColor: '#1976D2',
    borderRadius: 8,
    paddingVertical: 16,
    alignItems: 'center',
  },
  disabledButton: {
    backgroundColor: '#90CAF9',
  },
  submitButtonText: {
    color: '#FFF',
    fontSize: 16,
    fontWeight: '600',
  },
  loadingContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
  },
  emptyContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    padding: 20,
  },
  emptyText: {
    fontSize: 18,
    fontWeight: '500',
    color: '#757575',
    marginBottom: 8,
  },
  emptySubtext: {
    fontSize: 14,
    color: '#9E9E9E',
  },
});
