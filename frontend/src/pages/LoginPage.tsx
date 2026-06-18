import React, { useState } from 'react';
import { Form, Input, Button, Checkbox, message } from 'antd';
import { UserOutlined, LockOutlined } from '@ant-design/icons';
import { useNavigate, useLocation } from 'react-router-dom';
import { useAuthStore } from '@/store/auth';

interface LocationState {
  from?: {
    pathname: string;
  };
}

const LoginPage: React.FC = () => {
  const [loading, setLoading] = useState(false);
  const [form] = Form.useForm();
  const navigate = useNavigate();
  const location = useLocation();
  const { login } = useAuthStore();

  const handleSubmit = async (values: { email: string; password: string; remember: boolean }) => {
    setLoading(true);
    try {
      await login(values.email, values.password);
      message.success('登录成功');
      const state = location.state as LocationState;
      navigate(state?.from?.pathname || '/dashboards', { replace: true });
    } catch (err) {
      message.error('登录失败，请检查邮箱和密码');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="login-container">
      <div className="login-card">
        <h1 className="login-title">业务监控平台</h1>
        <p className="login-subtitle">数据驱动决策，智能监控预警</p>
        <Form
          form={form}
          name="login"
          initialValues={{ remember: true }}
          onFinish={handleSubmit}
          size="large"
        >
          <Form.Item
            name="email"
            rules={[
              { required: true, message: '请输入邮箱地址' },
              { type: 'email', message: '请输入有效的邮箱地址' },
            ]}
          >
            <Input prefix={<UserOutlined />} placeholder="邮箱地址" autoComplete="email" />
          </Form.Item>
          <Form.Item
            name="password"
            rules={[{ required: true, message: '请输入密码' }, { min: 6, message: '密码至少6位' }]}
          >
            <Input.Password
              prefix={<LockOutlined />}
              placeholder="密码"
              autoComplete="current-password"
            />
          </Form.Item>
          <Form.Item>
            <div style={{ display: 'flex', justifyContent: 'space-between' }}>
              <Form.Item name="remember" valuePropName="checked" noStyle>
                <Checkbox>记住我</Checkbox>
              </Form.Item>
            </div>
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" loading={loading} block>
              登录
            </Button>
          </Form.Item>
        </Form>
      </div>
    </div>
  );
};

export default LoginPage;
