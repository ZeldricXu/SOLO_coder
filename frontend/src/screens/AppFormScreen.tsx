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
import { appApi } from '../services/api';

type Props = {
  route: RouteProp<AppNavigatorParamList, 'AppForm'>;
  navigation: StackNavigationProp<AppNavigatorParamList, 'AppForm'>;
};

const PLATFORMS = [
  { label: 'Android', value: 'android' },
  { label: 'iOS', value: 'ios' },
  { label: 'HarmonyOS', value: 'harmonyos' },
];

const CATEGORIES = [
  { label: '工具', value: 'tools' },
  { label: '社交', value: 'social' },
  { label: '游戏', value: 'game' },
  { label: '娱乐', value: 'entertainment' },
  { label: '教育', value: 'education' },
  { label: '商务', value: 'business' },
  { label: '生活', value: 'lifestyle' },
  { label: '健康', value: 'health' },
];

const STATUSES = [
  { label: '草稿', value: 'draft' },
  { label: '已上线', value: 'active' },
  { label: '已下架', value: 'suspended' },
];

export default function AppFormScreen({ route, navigation }: Props) {
  const { appId } = route.params || {};
  const isEdit = !!appId;

  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [form, setForm] = useState({
    name: '',
    icon: '',
    description: '',
    category: 'tools',
    platform: 'android',
    status: 'draft',
  });

  useEffect(() => {
    if (isEdit && appId) {
      loadAppData(appId);
    }
  }, [appId, isEdit]);

  const loadAppData = async (id: string) => {
    try {
      setLoading(true);
      const response = await appApi.getApp(id);
      const app = response.data.data;
      setForm({
        name: app.name,
        icon: app.icon || '',
        description: app.description || '',
        category: app.category,
        platform: app.platform,
        status: app.status,
      });
    } catch (error: any) {
      Alert.alert('加载失败', error.message);
    } finally {
      setLoading(false);
    }
  };

  const validateForm = () => {
    if (!form.name.trim()) {
      Alert.alert('验证失败', '应用名称不能为空');
      return false;
    }
    if (!form.platform) {
      Alert.alert('验证失败', '请选择平台');
      return false;
    }
    if (!form.category) {
      Alert.alert('验证失败', '请选择分类');
      return false;
    }
    return true;
  };

  const handleSubmit = async () => {
    if (!validateForm()) return;

    try {
      setSaving(true);
      if (isEdit && appId) {
        await appApi.updateApp(appId, form);
        Alert.alert('成功', '应用信息已更新', [
          { text: '确定', onPress: () => navigation.goBack() },
        ]);
      } else {
        await appApi.createApp({
          ...form,
          developerId: 'dev_001',
        });
        Alert.alert('成功', '应用创建成功', [
          { text: '确定', onPress: () => navigation.goBack() },
        ]);
      }
    } catch (error: any) {
      Alert.alert('操作失败', error.response?.data?.message || error.message);
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return (
      <View style={styles.loadingContainer}>
        <ActivityIndicator size="large" color="#1976D2" />
      </View>
    );
  }

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.contentContainer}>
      <View style={styles.formGroup}>
        <Text style={styles.label}>应用名称 *</Text>
        <TextInput
          style={styles.input}
          value={form.name}
          onChangeText={(text) => setForm({ ...form, name: text })}
          placeholder="请输入应用名称"
          placeholderTextColor="#9E9E9E"
        />
      </View>

      <View style={styles.formGroup}>
        <Text style={styles.label}>图标URL</Text>
        <TextInput
          style={styles.input}
          value={form.icon}
          onChangeText={(text) => setForm({ ...form, icon: text })}
          placeholder="请输入图标URL（可选）"
          placeholderTextColor="#9E9E9E"
        />
      </View>

      <View style={styles.formGroup}>
        <Text style={styles.label}>平台 *</Text>
        <View style={styles.pickerContainer}>
          <Picker
            selectedValue={form.platform}
            onValueChange={(value) => setForm({ ...form, platform: value })}
            style={styles.picker}
          >
            {PLATFORMS.map((p) => (
              <Picker.Item key={p.value} label={p.label} value={p.value} />
            ))}
          </Picker>
        </View>
      </View>

      <View style={styles.formGroup}>
        <Text style={styles.label}>分类 *</Text>
        <View style={styles.pickerContainer}>
          <Picker
            selectedValue={form.category}
            onValueChange={(value) => setForm({ ...form, category: value })}
            style={styles.picker}
          >
            {CATEGORIES.map((c) => (
              <Picker.Item key={c.value} label={c.label} value={c.value} />
            ))}
          </Picker>
        </View>
      </View>

      {isEdit && (
        <View style={styles.formGroup}>
          <Text style={styles.label}>状态</Text>
          <View style={styles.pickerContainer}>
            <Picker
              selectedValue={form.status}
              onValueChange={(value) => setForm({ ...form, status: value })}
              style={styles.picker}
            >
              {STATUSES.map((s) => (
                <Picker.Item key={s.value} label={s.label} value={s.value} />
              ))}
            </Picker>
          </View>
        </View>
      )}

      <View style={styles.formGroup}>
        <Text style={styles.label}>应用描述</Text>
        <TextInput
          style={[styles.input, styles.multilineInput]}
          value={form.description}
          onChangeText={(text) => setForm({ ...form, description: text })}
          placeholder="请输入应用描述（可选）"
          placeholderTextColor="#9E9E9E"
          multiline
          numberOfLines={4}
          textAlignVertical="top"
        />
      </View>

      <TouchableOpacity
        style={[styles.submitButton, saving && styles.disabledButton]}
        onPress={handleSubmit}
        disabled={saving}
      >
        {saving ? (
          <ActivityIndicator size="small" color="#FFF" />
        ) : (
          <Text style={styles.submitButtonText}>
            {isEdit ? '保存修改' : '创建应用'}
          </Text>
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
    minHeight: 100,
    paddingTop: 12,
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
  submitButton: {
    backgroundColor: '#1976D2',
    borderRadius: 8,
    paddingVertical: 16,
    alignItems: 'center',
    marginTop: 8,
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
});
