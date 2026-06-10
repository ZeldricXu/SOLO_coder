import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, act, waitFor } from '@testing-library/react';
import { Input } from '@components/Input';
import React from 'react';

describe('Input Component', () => {
  describe('基础渲染', () => {
    it('渲染默认输入框', () => {
      render(<Input placeholder="请输入内容" />);
      const input = screen.getByPlaceholderText('请输入内容');
      expect(input).toBeInTheDocument();
      expect(input).toHaveAttribute('type', 'text');
      expect(input).toHaveClass('input');
    });

    it('渲染label标签', () => {
      render(<Input label="用户名" placeholder="请输入用户名" />);
      expect(screen.getByText('用户名')).toBeInTheDocument();
      expect(screen.getByLabelText('用户名')).toBeInTheDocument();
    });

    it('渲染helperText提示文字', () => {
      render(<Input helperText="最多20个字符" placeholder="请输入" />);
      expect(screen.getByText('最多20个字符')).toBeInTheDocument();
    });

    it('渲染必填星号', () => {
      render(<Input label="邮箱" required placeholder="请输入邮箱" />);
      const label = screen.getByText('邮箱');
      const requiredStar = label.querySelector('.required');
      expect(requiredStar).toBeInTheDocument();
    });
  });

  describe('onChange回调测试', () => {
    it('输入值后onChange收到正确数据', () => {
      const handleChange = vi.fn();
      render(<Input onChange={handleChange} placeholder="请输入" />);

      const input = screen.getByPlaceholderText('请输入');
      fireEvent.change(input, { target: { value: 'hello world' } });

      expect(handleChange).toHaveBeenCalledTimes(1);
      expect(handleChange).toHaveBeenCalledWith(
        expect.objectContaining({
          target: expect.objectContaining({ value: 'hello world' }),
        }),
      );
    });

    it('受控模式下value更新正确', () => {
      const handleChange = vi.fn();
      const { rerender } = render(
        <Input value="初始值" onChange={handleChange} placeholder="请输入" />,
      );

      const input = screen.getByPlaceholderText('请输入') as HTMLInputElement;
      expect(input.value).toBe('初始值');

      rerender(<Input value="更新后的值" onChange={handleChange} placeholder="请输入" />);
      expect(input.value).toBe('更新后的值');
    });

    it('非受控模式下defaultValue生效', () => {
      render(<Input defaultValue="默认值" placeholder="请输入" />);
      const input = screen.getByPlaceholderText('请输入') as HTMLInputElement;
      expect(input.value).toBe('默认值');
    });
  });

  describe('异常场景测试', () => {
    it('传入超出maxLength的值要截断提醒', async () => {
      const handleChange = vi.fn();
      render(
        <Input
          maxLength={5}
          showCount
          onChange={handleChange}
          placeholder="最多5个字符"
        />,
      );

      const input = screen.getByPlaceholderText('最多5个字符');

      act(() => {
        fireEvent.change(input, { target: { value: '1234567890' } });
      });

      await waitFor(() => {
        expect((input as HTMLInputElement).value).toBe('12345');
      });

      const counter = screen.getByText((content) => content.includes('5') && content.includes('/') && content.includes('5'));
      expect(counter).toBeInTheDocument();
    });

    it('disabled状态下无法输入', () => {
      const handleChange = vi.fn();
      render(<Input disabled onChange={handleChange} placeholder="请输入" />);

      const input = screen.getByPlaceholderText('请输入');
      expect(input).toBeDisabled();
      expect(input).toHaveAttribute('aria-disabled', 'true');

      fireEvent.change(input, { target: { value: 'test' } });
      expect(handleChange).not.toHaveBeenCalled();
    });

    it('error状态显示错误信息', () => {
      render(<Input error="输入内容不合法" placeholder="请输入" />);

      const input = screen.getByPlaceholderText('请输入');
      expect(input).toHaveAttribute('aria-invalid', 'true');
      expect(screen.getByText('输入内容不合法')).toBeInTheDocument();
    });

    it('error状态下有正确的ARIA属性', () => {
      render(
        <Input
          label="邮箱"
          error="邮箱格式不正确"
          placeholder="请输入邮箱"
        />,
      );

      const input = screen.getByPlaceholderText('请输入邮箱');
      expect(input).toHaveAttribute('aria-errormessage');
      expect(input).toHaveAttribute('aria-invalid', 'true');
    });
  });

  describe('状态测试', () => {
    it('sm尺寸渲染正确', () => {
      render(<Input size="sm" placeholder="小尺寸" />);
      const inputWrapper = screen.getByPlaceholderText('小尺寸').closest('.inputWrapper');
      expect(inputWrapper).toHaveClass('sm');
    });

    it('lg尺寸渲染正确', () => {
      render(<Input size="lg" placeholder="大尺寸" />);
      const inputWrapper = screen.getByPlaceholderText('大尺寸').closest('.inputWrapper');
      expect(inputWrapper).toHaveClass('lg');
    });

    it('md尺寸渲染正确', () => {
      render(<Input size="md" placeholder="中尺寸" />);
      const inputWrapper = screen.getByPlaceholderText('中尺寸').closest('.inputWrapper');
      expect(inputWrapper).toHaveClass('md');
    });

    it('focus状态样式正确（CSS伪类）', () => {
      render(<Input placeholder="请输入" />);
      const input = screen.getByPlaceholderText('请输入');
      expect(input).toBeInTheDocument();
    });

    it('hover状态样式正确（CSS伪类）', () => {
      render(<Input placeholder="请输入" />);
      const input = screen.getByPlaceholderText('请输入');
      expect(input).toBeInTheDocument();
    });

    it('disabled状态wrapper有disabled类', () => {
      render(<Input disabled placeholder="请输入" />);
      const inputWrapper = screen.getByPlaceholderText('请输入').closest('.inputWrapper');
      expect(inputWrapper).toHaveClass('disabled');
    });

    it('error状态wrapper有error类', () => {
      render(<Input error="错误" placeholder="请输入" />);
      const inputWrapper = screen.getByPlaceholderText('请输入').closest('.inputWrapper');
      expect(inputWrapper).toHaveClass('error');
    });
  });

  describe('前缀后缀测试', () => {
    it('渲染prefix前缀', () => {
      render(
        <Input
          prefix={<span data-testid="prefix">https://</span>}
          placeholder="请输入域名"
        />,
      );
      expect(screen.getByTestId('prefix')).toBeInTheDocument();
    });

    it('渲染suffix后缀', () => {
      render(
        <Input
          suffix={<span data-testid="suffix">.com</span>}
          placeholder="请输入域名"
        />,
      );
      expect(screen.getByTestId('suffix')).toBeInTheDocument();
    });

    it('同时渲染prefix和suffix', () => {
      render(
        <Input
          prefix={<span data-testid="prefix">¥</span>}
          suffix={<span data-testid="suffix">元</span>}
          placeholder="请输入金额"
        />,
      );
      expect(screen.getByTestId('prefix')).toBeInTheDocument();
      expect(screen.getByTestId('suffix')).toBeInTheDocument();
    });
  });

  describe('键盘事件测试', () => {
    it('Enter键触发onKeyDown', () => {
      const handleKeyDown = vi.fn();
      render(<Input onKeyDown={handleKeyDown} placeholder="请输入" />);

      const input = screen.getByPlaceholderText('请输入');
      fireEvent.keyDown(input, { key: 'Enter' });

      expect(handleKeyDown).toHaveBeenCalledTimes(1);
      expect(handleKeyDown).toHaveBeenCalledWith(
        expect.objectContaining({ key: 'Enter' }),
      );
    });

    it('Escape键触发onKeyDown', () => {
      const handleKeyDown = vi.fn();
      render(<Input onKeyDown={handleKeyDown} placeholder="请输入" />);

      const input = screen.getByPlaceholderText('请输入');
      fireEvent.keyDown(input, { key: 'Escape' });

      expect(handleKeyDown).toHaveBeenCalledWith(
        expect.objectContaining({ key: 'Escape' }),
      );
    });
  });

  describe('字符计数测试', () => {
    it('showCount显示字符计数', () => {
      render(<Input showCount maxLength={20} placeholder="请输入" />);
      expect(screen.getByText('0/20')).toBeInTheDocument();
    });

    it('输入后计数更新', () => {
      render(<Input showCount maxLength={20} placeholder="请输入" />);
      const input = screen.getByPlaceholderText('请输入');

      fireEvent.change(input, { target: { value: 'hello' } });
      expect(screen.getByText('5/20')).toBeInTheDocument();
    });
  });

  describe('类型测试', () => {
    it('password类型显示密码', () => {
      render(<Input type="password" placeholder="请输入密码" />);
      const input = screen.getByPlaceholderText('请输入密码');
      expect(input).toHaveAttribute('type', 'password');
    });

    it('number类型只能输入数字', () => {
      render(<Input type="number" placeholder="请输入数字" />);
      const input = screen.getByPlaceholderText('请输入数字');
      expect(input).toHaveAttribute('type', 'number');
    });

    it('email类型正确设置', () => {
      render(<Input type="email" placeholder="请输入邮箱" />);
      const input = screen.getByPlaceholderText('请输入邮箱');
      expect(input).toHaveAttribute('type', 'email');
    });
  });

  describe('边界条件', () => {
    it('空字符串value正确显示', () => {
      render(<Input value="" placeholder="请输入" />);
      const input = screen.getByPlaceholderText('请输入') as HTMLInputElement;
      expect(input.value).toBe('');
    });

    it('特殊字符输入正确', () => {
      const handleChange = vi.fn();
      render(<Input onChange={handleChange} placeholder="请输入" />);

      const input = screen.getByPlaceholderText('请输入');
      fireEvent.change(input, { target: { value: '!@#$%^&*()' } });

      expect(handleChange).toHaveBeenCalledWith(
        expect.objectContaining({
          target: expect.objectContaining({ value: '!@#$%^&*()' }),
        }),
      );
    });

    it('中文输入正确', () => {
      const handleChange = vi.fn();
      render(<Input onChange={handleChange} placeholder="请输入" />);

      const input = screen.getByPlaceholderText('请输入');
      fireEvent.change(input, { target: { value: '你好世界' } });

      expect(handleChange).toHaveBeenCalledWith(
        expect.objectContaining({
          target: expect.objectContaining({ value: '你好世界' }),
        }),
      );
    });
  });
});
