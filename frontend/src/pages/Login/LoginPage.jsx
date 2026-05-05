import React, { useState } from 'react';
import {
  Card,
  Form,
  Input,
  Button,
  message,
  Checkbox,
  Divider,
} from 'antd';
import { UserOutlined, LockOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { authApi } from '../../services/api';
import './LoginPage.css';

const LoginPage = () => {
  const navigate = useNavigate();
  const [loading, setLoading] = useState(false);

  const onFinish = async (values) => {
    try {
      setLoading(true);
      
      message.info('演示模式：直接进入系统');
      
      const mockUser = {
        user_id: 'user_org_01',
        username: values.username,
        email: values.username + '@example.com',
        role: 'organizer',
      };
      
      localStorage.setItem('token', 'mock_jwt_token');
      localStorage.setItem('user', JSON.stringify(mockUser));
      
      message.success('登录成功');
      navigate('/events');
    } catch (error) {
      message.error('登录失败，请检查用户名和密码');
      console.error(error);
    } finally {
      setLoading(false);
    }
  };

  const handleDemoLogin = () => {
    const mockUser = {
      user_id: 'user_org_01',
      username: 'demo_user',
      email: 'demo@example.com',
      role: 'organizer',
    };
    
    localStorage.setItem('token', 'mock_jwt_token');
    localStorage.setItem('user', JSON.stringify(mockUser));
    
    message.success('演示登录成功');
    navigate('/events');
  };

  return (
    <div className="login-page">
      <div className="login-background">
        <div className="login-shape shape-1"></div>
        <div className="login-shape shape-2"></div>
        <div className="login-shape shape-3"></div>
      </div>
      
      <div className="login-container">
        <div className="login-header">
          <h1 className="login-title">EventHub</h1>
          <p className="login-subtitle">活动管理平台</p>
        </div>
        
        <Card className="login-card">
          <Form
            name="login"
            layout="vertical"
            onFinish={onFinish}
            autoComplete="off"
            size="large"
          >
            <Form.Item
              name="username"
              label="用户名"
              rules={[{ required: true, message: '请输入用户名' }]}
            >
              <Input
                prefix={<UserOutlined className="input-icon" />}
                placeholder="请输入用户名"
              />
            </Form.Item>

            <Form.Item
              name="password"
              label="密码"
              rules={[{ required: true, message: '请输入密码' }]}
            >
              <Input.Password
                prefix={<LockOutlined className="input-icon" />}
                placeholder="请输入密码"
              />
            </Form.Item>

            <Form.Item>
              <div className="login-options">
                <Checkbox>记住我</Checkbox>
                <a className="forgot-password" href="#">
                  忘记密码？
                </a>
              </div>
            </Form.Item>

            <Form.Item>
              <Button
                type="primary"
                htmlType="submit"
                block
                size="large"
                loading={loading}
                className="login-button"
              >
                登录
              </Button>
            </Form.Item>
          </Form>

          <Divider className="login-divider">或者</Divider>

          <Button
            block
            size="large"
            onClick={handleDemoLogin}
            className="demo-button"
          >
            演示模式登录
          </Button>

          <p className="login-footer">
            还没有账户？
            <a className="register-link" href="#">
              立即注册
            </a>
          </p>
        </Card>

        <div className="login-info">
          <p>EventHub 活动管理平台 © 2026</p>
          <p className="login-info-sub">活动创建 · 报名管理 · 签到统计</p>
        </div>
      </div>
    </div>
  );
};

export default LoginPage;
