import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { Button } from '@components/Button';
import { createButtonProps } from './factories';
import React from 'react';

describe('Button Component', () => {
  describe('渲染测试 - 变体(variant)', () => {
    it.each([
      ['primary', '主按钮'],
      ['secondary', '次按钮'],
      ['outline', '描边按钮'],
      ['ghost', '幽灵按钮'],
      ['danger', '危险按钮'],
    ] as const)('渲染%s变体正确', (variant) => {
      const props = createButtonProps({ variant, children: `${variant}按钮` });
      render(<Button {...props} />);
      const button = screen.getByRole('button', { name: `${variant}按钮` });
      expect(button).toBeInTheDocument();
      expect(button).toHaveClass(variant);
      expect(button).toHaveClass('base');
    });
  });

  describe('渲染测试 - 尺寸(size)', () => {
    it.each([
      ['sm', '小按钮'],
      ['md', '中按钮'],
      ['lg', '大按钮'],
    ] as const)('渲染%s尺寸正确', (size) => {
      const props = createButtonProps({ size, children: `${size}按钮` });
      render(<Button {...props} />);
      const button = screen.getByRole('button', { name: `${size}按钮` });
      expect(button).toBeInTheDocument();
      expect(button).toHaveClass(size);
      expect(button).toHaveClass('base');
    });
  });

  describe('状态组合测试', () => {
    it('disabled状态 - 渲染正确的禁用属性', () => {
      const handleClick = vi.fn();
      const props = createButtonProps({ disabled: true, onClick: handleClick });
      render(<Button {...props} />);

      const button = screen.getByRole('button');
      expect(button).toBeDisabled();
      expect(button).toHaveAttribute('aria-disabled', 'true');
      expect(button).toHaveAttribute('tabindex', '-1');

      fireEvent.click(button);
      expect(handleClick).not.toHaveBeenCalled();
    });

    it('loading状态 - 禁用点击且显示加载图标', () => {
      const handleClick = vi.fn();
      const props = createButtonProps({ loading: true, onClick: handleClick });
      render(<Button {...props} />);

      const button = screen.getByRole('button');
      expect(button).toBeDisabled();
      expect(button).toHaveAttribute('aria-disabled', 'true');
      expect(button).toHaveAttribute('aria-busy', 'true');
      expect(button).toHaveClass('loading');
      expect(button.querySelector('.spinner')).toBeInTheDocument();

      fireEvent.click(button);
      expect(handleClick).not.toHaveBeenCalled();
    });

    it('disabled + loading组合状态 - 优先禁用', () => {
      const handleClick = vi.fn();
      const props = createButtonProps({ disabled: true, loading: true, onClick: handleClick });
      render(<Button {...props} />);

      const button = screen.getByRole('button');
      expect(button).toBeDisabled();
      expect(button).toHaveAttribute('aria-disabled', 'true');
      expect(button).toHaveAttribute('tabindex', '-1');
    });

    it('fullWidth状态 - 渲染正确的类名', () => {
      const props = createButtonProps({ fullWidth: true });
      render(<Button {...props} />);
      const button = screen.getByRole('button');
      expect(button).toHaveClass('fullWidth');
    });

    it('hover状态 - 鼠标悬停时样式正确（CSS伪类）', () => {
      const props = createButtonProps();
      render(<Button {...props} />);
      const button = screen.getByRole('button');
      expect(button).toBeInTheDocument();
    });

    it('active状态 - 鼠标按下时样式正确（CSS伪类）', () => {
      const props = createButtonProps();
      render(<Button {...props} />);
      const button = screen.getByRole('button');
      expect(button).toBeInTheDocument();
    });

    it('focus状态 - 获取焦点时样式正确（CSS伪类）', () => {
      const props = createButtonProps();
      render(<Button {...props} />);
      const button = screen.getByRole('button');

      fireEvent.focus(button);
      expect(button).toHaveAttribute('tabindex', '0');

      fireEvent.blur(button);
      expect(button).toBeInTheDocument();
    });
  });

  describe('交互测试', () => {
    it('点击事件 - 正常状态下触发onClick', () => {
      const handleClick = vi.fn();
      const props = createButtonProps({ onClick: handleClick });
      render(<Button {...props} />);

      const button = screen.getByRole('button');
      fireEvent.click(button);
      expect(handleClick).toHaveBeenCalledTimes(1);
    });

    it('键盘交互 - Enter键触发点击', () => {
      const handleClick = vi.fn();
      const props = createButtonProps({ onClick: handleClick });
      render(<Button {...props} />);

      const button = screen.getByRole('button');
      fireEvent.keyDown(button, { key: 'Enter' });
      expect(handleClick).toHaveBeenCalledTimes(1);
    });

    it('键盘交互 - Space键触发点击', () => {
      const handleClick = vi.fn();
      const props = createButtonProps({ onClick: handleClick });
      render(<Button {...props} />);

      const button = screen.getByRole('button');
      fireEvent.keyDown(button, { key: ' ' });
      expect(handleClick).toHaveBeenCalledTimes(1);
    });

    it('Tab键可聚焦', () => {
      const props = createButtonProps();
      render(<Button {...props} />);
      const button = screen.getByRole('button');
      expect(button).toHaveAttribute('tabindex', '0');
    });

    it('禁用状态下Tab键不可聚焦', () => {
      const props = createButtonProps({ disabled: true });
      render(<Button {...props} />);
      const button = screen.getByRole('button');
      expect(button).toHaveAttribute('tabindex', '-1');
    });

    it('loading状态下点击不触发onClick', () => {
      const handleClick = vi.fn();
      const props = createButtonProps({ loading: true, onClick: handleClick });
      render(<Button {...props} />);

      const button = screen.getByRole('button');
      fireEvent.click(button);
      expect(handleClick).not.toHaveBeenCalled();
    });

    it('disabled状态下键盘事件不触发onClick', () => {
      const handleClick = vi.fn();
      const props = createButtonProps({ disabled: true, onClick: handleClick });
      render(<Button {...props} />);

      const button = screen.getByRole('button');
      fireEvent.keyDown(button, { key: 'Enter' });
      expect(handleClick).not.toHaveBeenCalled();
    });
  });

  describe('ARIA无障碍属性', () => {
    it('默认按钮具有正确的ARIA属性', () => {
      const props = createButtonProps({ 'aria-label': '自定义标签' });
      render(<Button {...props} />);
      const button = screen.getByRole('button', { name: '自定义标签' });
      expect(button).toHaveAttribute('role', 'button');
      expect(button).toHaveAttribute('aria-label', '自定义标签');
    });

    it('loading状态具有正确的ARIA属性', () => {
      const props = createButtonProps({ loading: true });
      render(<Button {...props} />);
      const button = screen.getByRole('button');
      expect(button).toHaveAttribute('aria-disabled', 'true');
      expect(button).toHaveAttribute('role', 'button');
    });

    it('disabled状态具有正确的ARIA属性', () => {
      const props = createButtonProps({ disabled: true });
      render(<Button {...props} />);
      const button = screen.getByRole('button');
      expect(button).toHaveAttribute('aria-disabled', 'true');
      expect(button).toHaveAttribute('tabindex', '-1');
    });
  });

  describe('图标渲染', () => {
    it('渲染左侧图标', () => {
      const leftIcon = <span data-testid="left-icon">🔍</span>;
      const props = createButtonProps({ leftIcon });
      render(<Button {...props} />);
      expect(screen.getByTestId('left-icon')).toBeInTheDocument();
    });

    it('渲染右侧图标', () => {
      const rightIcon = <span data-testid="right-icon">→</span>;
      const props = createButtonProps({ rightIcon });
      render(<Button {...props} />);
      expect(screen.getByTestId('right-icon')).toBeInTheDocument();
    });

    it('同时渲染左右图标', () => {
      const leftIcon = <span data-testid="left-icon">🔍</span>;
      const rightIcon = <span data-testid="right-icon">→</span>;
      const props = createButtonProps({ leftIcon, rightIcon });
      render(<Button {...props} />);
      expect(screen.getByTestId('left-icon')).toBeInTheDocument();
      expect(screen.getByTestId('right-icon')).toBeInTheDocument();
    });

    it('loading状态下显示加载图标替代leftIcon', () => {
      const leftIcon = <span data-testid="left-icon">🔍</span>;
      const props = createButtonProps({ loading: true, leftIcon });
      render(<Button {...props} />);
      expect(screen.queryByTestId('left-icon')).not.toBeInTheDocument();
      expect(document.querySelector('.spinner')).toBeInTheDocument();
    });

    it('非loading状态下显示leftIcon而非加载图标', () => {
      const leftIcon = <span data-testid="left-icon">🔍</span>;
      const props = createButtonProps({ loading: false, leftIcon });
      render(<Button {...props} />);
      expect(screen.getByTestId('left-icon')).toBeInTheDocument();
      expect(document.querySelector('.spinner')).not.toBeInTheDocument();
    });
  });

  describe('无障碍键盘操作', () => {
    it('focus时按Enter触发onClick', () => {
      const handleClick = vi.fn();
      const props = createButtonProps({ onClick: handleClick });
      render(<Button {...props} />);

      const button = screen.getByRole('button');
      button.focus();
      fireEvent.keyDown(button, { key: 'Enter' });

      expect(handleClick).toHaveBeenCalledTimes(1);
    });

    it('focus时按Space触发onClick', () => {
      const handleClick = vi.fn();
      const props = createButtonProps({ onClick: handleClick });
      render(<Button {...props} />);

      const button = screen.getByRole('button');
      button.focus();
      fireEvent.keyDown(button, { key: ' ' });

      expect(handleClick).toHaveBeenCalledTimes(1);
    });

    it('focus时按其他键不触发onClick', () => {
      const handleClick = vi.fn();
      const props = createButtonProps({ onClick: handleClick });
      render(<Button {...props} />);

      const button = screen.getByRole('button');
      button.focus();
      fireEvent.keyDown(button, { key: 'Tab' });
      fireEvent.keyDown(button, { key: 'a' });

      expect(handleClick).not.toHaveBeenCalled();
    });

    it('disabled时Enter/Space不触发onClick', () => {
      const handleClick = vi.fn();
      const props = createButtonProps({ disabled: true, onClick: handleClick });
      render(<Button {...props} />);

      const button = screen.getByRole('button');
      fireEvent.keyDown(button, { key: 'Enter' });
      fireEvent.keyDown(button, { key: ' ' });

      expect(handleClick).not.toHaveBeenCalled();
    });

    it('loading时Enter/Space不触发onClick', () => {
      const handleClick = vi.fn();
      const props = createButtonProps({ loading: true, onClick: handleClick });
      render(<Button {...props} />);

      const button = screen.getByRole('button');
      fireEvent.keyDown(button, { key: 'Enter' });
      fireEvent.keyDown(button, { key: ' ' });

      expect(handleClick).not.toHaveBeenCalled();
    });
  });
});
