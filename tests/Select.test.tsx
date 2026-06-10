import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, act, waitFor } from '@testing-library/react';
import { Select } from '@components/Select';
import { createSelectOptions } from './factories';
import React from 'react';

describe('Select Component', () => {
  describe('基础渲染', () => {
    it('渲染默认选择器', () => {
      const options = createSelectOptions(5);
      render(<Select options={options} placeholder="请选择" />);
      expect(screen.getByText('请选择')).toBeInTheDocument();
    });

    it('渲染label标签', () => {
      const options = createSelectOptions(3);
      render(<Select options={options} label="城市" placeholder="请选择城市" />);
      expect(screen.getByText('城市')).toBeInTheDocument();
    });

    it('渲染helperText提示', () => {
      const options = createSelectOptions(3);
      render(<Select options={options} helperText="选择您所在的城市" placeholder="请选择" />);
      expect(screen.getByText('选择您所在的城市')).toBeInTheDocument();
    });

    it('渲染必填星号', () => {
      const options = createSelectOptions(3);
      render(<Select options={options} label="城市" required placeholder="请选择" />);
      const label = screen.getByText('城市');
      const requiredStar = label.querySelector('.required');
      expect(requiredStar).toBeInTheDocument();
      expect(requiredStar).toHaveTextContent('*');
    });
  });

  describe('下拉开关逻辑', () => {
    it('点击触发器打开下拉菜单', async () => {
      const options = createSelectOptions(3);
      render(<Select options={options} placeholder="请选择" />);

      const trigger = screen.getByRole('button', { name: /请选择/i });
      fireEvent.click(trigger);

      await waitFor(() => {
        expect(screen.getByRole('listbox')).toBeInTheDocument();
      });
      expect(trigger).toHaveAttribute('aria-expanded', 'true');
    });

    it('再次点击关闭下拉菜单', async () => {
      const options = createSelectOptions(3);
      render(<Select options={options} placeholder="请选择" />);

      const trigger = screen.getByRole('button', { name: /请选择/i });

      fireEvent.click(trigger);
      await waitFor(() => {
        expect(screen.getByRole('listbox')).toBeInTheDocument();
      });

      fireEvent.click(trigger);
      await waitFor(() => {
        expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
      });
      expect(trigger).toHaveAttribute('aria-expanded', 'false');
    });

    it('点击外部区域关闭下拉菜单', async () => {
      const options = createSelectOptions(3);
      render(
        <div>
          <div data-testid="outside">外部区域</div>
          <Select options={options} placeholder="请选择" />
        </div>,
      );

      const trigger = screen.getByRole('button', { name: /请选择/i });
      fireEvent.click(trigger);

      await waitFor(() => {
        expect(screen.getByRole('listbox')).toBeInTheDocument();
      });

      const outside = screen.getByTestId('outside');
      fireEvent.pointerDown(outside);
      fireEvent.click(outside);

      await waitFor(() => {
        expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
      });
    });

    it('Escape键关闭下拉菜单', async () => {
      const options = createSelectOptions(3);
      render(<Select options={options} placeholder="请选择" />);

      const trigger = screen.getByRole('button', { name: /请选择/i });
      fireEvent.click(trigger);

      await waitFor(() => {
        expect(screen.getByRole('listbox')).toBeInTheDocument();
      });

      fireEvent.keyDown(trigger, { key: 'Escape' });

      await waitFor(() => {
        expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
      });
    });
  });

  describe('选项选中逻辑', () => {
    it('点击选项触发onChange回调', async () => {
      const handleChange = vi.fn();
      const options = createSelectOptions(3);
      render(<Select options={options} onChange={handleChange} placeholder="请选择" />);

      const trigger = screen.getByRole('button', { name: /请选择/i });
      fireEvent.click(trigger);

      await waitFor(() => {
        expect(screen.getByRole('listbox')).toBeInTheDocument();
      });

      const option = screen.getByRole('option', { name: /Option 1/i });
      fireEvent.click(option);

      expect(handleChange).toHaveBeenCalledTimes(1);
      expect(handleChange).toHaveBeenCalledWith('option-1', options[0]);
    });

    it('选中选项后显示正确的label', async () => {
      const options = createSelectOptions(3);
      render(<Select options={options} placeholder="请选择" />);

      const trigger = screen.getByRole('button', { name: /请选择/i });
      fireEvent.click(trigger);

      await waitFor(() => {
        expect(screen.getByRole('listbox')).toBeInTheDocument();
      });

      const option = screen.getByRole('option', { name: /Option 2/i });
      fireEvent.click(option);

      await waitFor(() => {
        expect(screen.getByText('Option 2')).toBeInTheDocument();
      });
    });

    it('disabled选项不可选中', async () => {
      const handleChange = vi.fn();
      const options = createSelectOptions(3);
      render(<Select options={options} onChange={handleChange} placeholder="请选择" />);

      const trigger = screen.getByRole('button', { name: /请选择/i });
      fireEvent.click(trigger);

      await waitFor(() => {
        expect(screen.getByRole('listbox')).toBeInTheDocument();
      });

      const disabledOption = screen.getByRole('option', { name: /Option 3/i });
      expect(disabledOption).toHaveAttribute('aria-disabled', 'true');

      fireEvent.click(disabledOption);
      expect(handleChange).not.toHaveBeenCalled();
    });

    it('受控模式下value正确', () => {
      const handleChange = vi.fn();
      const options = createSelectOptions(3);
      const { rerender } = render(
        <Select options={options} value="option-1" onChange={handleChange} placeholder="请选择" />,
      );

      expect(screen.getByText('Option 1')).toBeInTheDocument();

      rerender(
        <Select options={options} value="option-2" onChange={handleChange} placeholder="请选择" />,
      );

      expect(screen.getByText('Option 2')).toBeInTheDocument();
    });

    it('defaultValue设置默认选中值', () => {
      const options = createSelectOptions(3);
      render(<Select options={options} defaultValue="option-2" placeholder="请选择" />);
      expect(screen.getByText('Option 2')).toBeInTheDocument();
    });
  });

  describe('键盘导航', () => {
    it('ArrowDown键打开下拉并选中第一项', async () => {
      const options = createSelectOptions(3);
      render(<Select options={options} placeholder="请选择" />);

      const trigger = screen.getByRole('button', { name: /请选择/i });
      fireEvent.keyDown(trigger, { key: 'ArrowDown' });

      await waitFor(() => {
        expect(screen.getByRole('listbox')).toBeInTheDocument();
      });
    });

    it('Enter键选中当前高亮项', async () => {
      const handleChange = vi.fn();
      const options = createSelectOptions(3);
      render(<Select options={options} onChange={handleChange} placeholder="请选择" />);

      const trigger = screen.getByRole('button', { name: /请选择/i });
      fireEvent.keyDown(trigger, { key: 'ArrowDown' });

      await waitFor(() => {
        expect(screen.getByRole('listbox')).toBeInTheDocument();
      });

      fireEvent.keyDown(trigger, { key: 'Enter' });

      expect(handleChange).toHaveBeenCalledWith('option-1', options[0]);
    });

    it('上下键导航选项', async () => {
      const options = createSelectOptions(5);
      render(<Select options={options} placeholder="请选择" />);

      const trigger = screen.getByRole('button', { name: /请选择/i });
      fireEvent.keyDown(trigger, { key: 'ArrowDown' });

      await waitFor(() => {
        expect(screen.getByRole('listbox')).toBeInTheDocument();
      });

      fireEvent.keyDown(trigger, { key: 'ArrowDown' });
      fireEvent.keyDown(trigger, { key: 'ArrowDown' });
      fireEvent.keyDown(trigger, { key: 'Enter' });

      const displayedOption = screen.getByText('Option 3');
      expect(displayedOption).toBeInTheDocument();
    });
  });

  describe('边界条件', () => {
    it('没有选项时显示空状态提示', () => {
      render(<Select options={[]} placeholder="请选择" />);

      const trigger = screen.getByRole('button', { name: /请选择/i });
      fireEvent.click(trigger);

      expect(screen.getByText('暂无数据')).toBeInTheDocument();
    });

    it('空选项数组可打开下拉显示空状态', async () => {
      render(<Select options={[]} placeholder="请选择" />);

      const trigger = screen.getByRole('button', { name: /请选择/i });
      expect(trigger).not.toBeDisabled();

      fireEvent.click(trigger);
      await waitFor(() => {
        expect(screen.getByRole('listbox')).toBeInTheDocument();
      });
      expect(screen.getByText('暂无数据')).toBeInTheDocument();
    });

    it('clearable支持清除选择', async () => {
      const handleChange = vi.fn();
      const options = createSelectOptions(3);
      render(
        <Select
          options={options}
          defaultValue="option-1"
          clearable
          onChange={handleChange}
          placeholder="请选择"
        />,
      );

      const clearBtn = screen.getByRole('button', { name: /清除选择/i });
      fireEvent.click(clearBtn);

      expect(handleChange).toHaveBeenCalledWith('', null);
      expect(screen.getByText('请选择')).toBeInTheDocument();
    });
  });

  describe('状态测试', () => {
    it('disabled状态不可打开下拉', () => {
      const options = createSelectOptions(3);
      render(<Select options={options} disabled placeholder="请选择" />);

      const trigger = screen.getByRole('button', { name: /请选择/i });
      expect(trigger).toBeDisabled();

      fireEvent.click(trigger);
      expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
    });

    it('error状态显示错误信息', () => {
      const options = createSelectOptions(3);
      render(<Select options={options} error="请选择一个选项" placeholder="请选择" />);

      const trigger = screen.getByRole('button', { name: /请选择/i });
      expect(trigger).toHaveAttribute('aria-invalid', 'true');
      expect(screen.getByText('请选择一个选项')).toBeInTheDocument();
    });

    it('sm尺寸渲染正确', () => {
      const options = createSelectOptions(3);
      render(<Select options={options} size="sm" placeholder="请选择" />);

      const trigger = screen.getByRole('button', { name: /请选择/i });
      expect(trigger).toHaveClass('trigger');
      expect(trigger).toHaveClass('sm');
    });

    it('lg尺寸渲染正确', () => {
      const options = createSelectOptions(3);
      render(<Select options={options} size="lg" placeholder="请选择" />);

      const trigger = screen.getByRole('button', { name: /请选择/i });
      expect(trigger).toHaveClass('trigger');
      expect(trigger).toHaveClass('lg');
    });

    it('error状态下trigger有error类', () => {
      const options = createSelectOptions(3);
      render(<Select options={options} error="请选择一个选项" placeholder="请选择" />);

      const trigger = screen.getByRole('button', { name: /请选择/i });
      expect(trigger).toHaveClass('error');
    });

    it('helperText有helperText类', () => {
      const options = createSelectOptions(3);
      render(<Select options={options} helperText="选择您所在的城市" placeholder="请选择" />);

      const helperText = screen.getByText('选择您所在的城市');
      expect(helperText).toHaveClass('helperText');
    });

    it('error状态下helperText有error类', () => {
      const options = createSelectOptions(3);
      render(<Select options={options} error="请选择一个选项" placeholder="请选择" />);

      const errorText = screen.getByText('请选择一个选项');
      expect(errorText).toHaveClass('helperText');
      expect(errorText).toHaveClass('error');
    });
  });

  describe('ARIA无障碍属性', () => {
    it('触发器具有正确的ARIA属性', () => {
      const options = createSelectOptions(3);
      render(<Select options={options} label="城市" placeholder="请选择" />);

      const trigger = screen.getByRole('button', { name: /城市/i });
      expect(trigger).toHaveAttribute('aria-haspopup', 'listbox');
      expect(trigger).toHaveAttribute('aria-expanded', 'false');
    });

    it('打开后设置正确的aria-expanded', async () => {
      const options = createSelectOptions(3);
      render(<Select options={options} placeholder="请选择" />);

      const trigger = screen.getByRole('button', { name: /请选择/i });
      fireEvent.click(trigger);

      await waitFor(() => {
        expect(trigger).toHaveAttribute('aria-expanded', 'true');
      });
    });

    it('选项具有正确的ARIA属性', async () => {
      const options = createSelectOptions(3);
      render(<Select options={options} placeholder="请选择" />);

      const trigger = screen.getByRole('button', { name: /请选择/i });
      fireEvent.click(trigger);

      await waitFor(() => {
        expect(screen.getByRole('listbox')).toBeInTheDocument();
      });

      const option = screen.getByRole('option', { name: /Option 1/i });
      expect(option).toHaveAttribute('role', 'option');
      expect(option).toHaveAttribute('aria-selected', 'false');
    });

    it('选中选项设置aria-selected为true', async () => {
      const options = createSelectOptions(3);
      render(<Select options={options} defaultValue="option-1" placeholder="请选择" />);

      const trigger = screen.getByRole('button');
      fireEvent.click(trigger);

      await waitFor(() => {
        expect(screen.getByRole('listbox')).toBeInTheDocument();
      });

      const selectedOption = screen.getByRole('option', { name: /Option 1/i });
      expect(selectedOption).toHaveAttribute('aria-selected', 'true');
    });
  });

  describe('CSS类名检查', () => {
    it('下拉菜单有dropdown类', async () => {
      const options = createSelectOptions(3);
      render(<Select options={options} placeholder="请选择" />);

      const trigger = screen.getByRole('button', { name: /请选择/i });
      fireEvent.click(trigger);

      await waitFor(() => {
        expect(screen.getByRole('listbox')).toBeInTheDocument();
      });

      const dropdown = screen.getByRole('listbox');
      expect(dropdown).toHaveClass('dropdown');
    });

    it('选项有option类，选中的选项有active类', async () => {
      const options = createSelectOptions(3);
      render(<Select options={options} defaultValue="option-1" placeholder="请选择" />);

      const trigger = screen.getByRole('button');
      fireEvent.click(trigger);

      await waitFor(() => {
        expect(screen.getByRole('listbox')).toBeInTheDocument();
      });

      const option1 = screen.getByRole('option', { name: /Option 1/i });
      const option2 = screen.getByRole('option', { name: /Option 2/i });

      expect(option1).toHaveClass('option');
      expect(option1).toHaveClass('active');
      expect(option2).toHaveClass('option');
      expect(option2).not.toHaveClass('active');
    });

    it('disabled选项有disabled类', async () => {
      const options = createSelectOptions(3);
      render(<Select options={options} placeholder="请选择" />);

      const trigger = screen.getByRole('button', { name: /请选择/i });
      fireEvent.click(trigger);

      await waitFor(() => {
        expect(screen.getByRole('listbox')).toBeInTheDocument();
      });

      const disabledOption = screen.getByRole('option', { name: /Option 3/i });
      expect(disabledOption).toHaveClass('option');
      expect(disabledOption).toHaveClass('disabled');
    });

    it('空状态有emptyState类', async () => {
      render(<Select options={[]} placeholder="请选择" />);

      const trigger = screen.getByRole('button', { name: /请选择/i });
      fireEvent.click(trigger);

      await waitFor(() => {
        expect(screen.getByRole('listbox')).toBeInTheDocument();
      });

      const emptyState = screen.getByText('暂无数据');
      expect(emptyState).toHaveClass('emptyState');
    });

    it('打开状态下箭头有open类', async () => {
      const options = createSelectOptions(3);
      render(<Select options={options} placeholder="请选择" />);

      const trigger = screen.getByRole('button', { name: /请选择/i });
      const arrow = trigger.querySelector('.arrow');

      expect(arrow).not.toHaveClass('open');

      fireEvent.click(trigger);
      await waitFor(() => {
        expect(screen.getByRole('listbox')).toBeInTheDocument();
      });

      expect(arrow).toHaveClass('open');
    });

    it('placeholder状态下value有placeholder类', () => {
      const options = createSelectOptions(3);
      render(<Select options={options} placeholder="请选择" />);

      const trigger = screen.getByRole('button', { name: /请选择/i });
      const valueSpan = trigger.querySelector('.value');

      expect(valueSpan).toHaveClass('placeholder');
    });

    it('wrapper有wrapper类', () => {
      const options = createSelectOptions(3);
      const { container } = render(<Select options={options} placeholder="请选择" />);

      const wrapper = container.firstChild;
      expect(wrapper).toHaveClass('wrapper');
    });

    it('label有label类', () => {
      const options = createSelectOptions(3);
      render(<Select options={options} label="城市" placeholder="请选择" />);

      const label = screen.getByText('城市');
      expect(label).toHaveClass('label');
    });
  });
});
