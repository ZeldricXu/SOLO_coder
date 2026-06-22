import { useState, useEffect } from 'react'
import {
  Card,
  Form,
  Switch,
  Checkbox,
  Button,
  Space,
  Tabs,
  message,
  Divider,
} from 'antd'
import {
  BellOutlined,
  SettingOutlined,
  UserOutlined,
} from '@ant-design/icons'
import { notificationApi, authApi } from '@/api'
import type { NotificationPreference, User } from '@/types'
import { useAuthStore } from '@/store'

function Settings() {
  const [prefs, setPrefs] = useState<NotificationPreference | null>(null)
  const [user, setUser] = useState<User | null>(null)
  const [form] = Form.useForm()
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    loadPreferences()
    loadUserInfo()
  }, [])

  const loadPreferences = async () => {
    try {
      const { data } = await notificationApi.getPreferences()
      setPrefs(data)
      form.setFieldsValue({
        booking_confirm: data.booking_confirm,
        upcoming_remind: data.upcoming_remind,
        minutes_release: data.minutes_release,
        todo_assign: data.todo_assign,
        channels: data.channels.split(','),
      })
    } catch (error) {
      message.error('加载设置失败')
    }
  }

  const loadUserInfo = async () => {
    try {
      const { data } = await authApi.me()
      setUser(data)
    } catch (error) {
      // ignore
    }
  }

  const handleSave = async (values: any) => {
    setLoading(true)
    try {
      await notificationApi.updatePreferences({
        booking_confirm: values.booking_confirm,
        upcoming_remind: values.upcoming_remind,
        minutes_release: values.minutes_release,
        todo_assign: values.todo_assign,
        channels: values.channels.join(','),
      })
      message.success('设置保存成功')
      loadPreferences()
    } catch (error: any) {
      message.error(error.response?.data?.error || '保存失败')
    } finally {
      setLoading(false)
    }
  }

  const tabItems = [
    { key: 'notification', label: '通知设置', icon: <BellOutlined /> },
    { key: 'account', label: '账号信息', icon: <UserOutlined /> },
    { key: 'system', label: '系统设置', icon: <SettingOutlined /> },
  ]

  const channelOptions = [
    { label: '企业微信', value: 'wechat' },
    { label: '钉钉', value: 'dingtalk' },
    { label: '飞书', value: 'feishu' },
    { label: '邮件', value: 'email' },
  ]

  return (
    <Card title="系统设置">
      <Tabs items={tabItems} defaultActiveKey="notification">
        {{
          key: 'notification',
          children: (
            <Form form={form} layout="vertical" onFinish={handleSave} style={{ maxWidth: 500 }}>
              <Divider orientation="left">通知类型</Divider>
              <Form.Item name="booking_confirm" label="预订确认通知" valuePropName="checked">
                <Switch />
              </Form.Item>
              <Form.Item name="upcoming_remind" label="会议临近提醒" valuePropName="checked">
                <Switch />
              </Form.Item>
              <Form.Item name="minutes_release" label="纪要发布通知" valuePropName="checked">
                <Switch />
              </Form.Item>
              <Form.Item name="todo_assign" label="待办分配通知" valuePropName="checked">
                <Switch />
              </Form.Item>

              <Divider orientation="left">通知渠道</Divider>
              <Form.Item name="channels" label="接收渠道">
                <Checkbox.Group options={channelOptions} />
              </Form.Item>

              <Form.Item>
                <Space>
                  <Button type="primary" htmlType="submit" loading={loading}>
                    保存设置
                  </Button>
                </Space>
              </Form.Item>
            </Form>
          ),
        }}
        {{
          key: 'account',
          children: (
            <div style={{ maxWidth: 500 }}>
              <Card type="inner" title="个人信息">
                <p><strong>姓名：</strong>{user?.name}</p>
                <p><strong>邮箱：</strong>{user?.email}</p>
                <p><strong>部门：</strong>{user?.department || '-'}</p>
                <p><strong>角色：</strong>{user?.role === 'admin' ? '管理员' : '普通用户'}</p>
              </Card>
            </div>
          ),
        }}
        {{
          key: 'system',
          children: (
            <div style={{ maxWidth: 500 }}>
              <Card type="inner" title="关于系统">
                <p><strong>系统名称：</strong>会议室预约系统</p>
                <p><strong>版本号：</strong>v1.0.0</p>
                <p><strong>技术栈：</strong>Go + React + PostgreSQL</p>
                <p style={{ color: '#999' }}>
                  高效会议室预约与会议协作平台，支持会议室管理、在线预约、实时协作、数据统计等功能。
                </p>
              </Card>
            </div>
          ),
        }}
      </Tabs>
    </Card>
  )
}

export default Settings
