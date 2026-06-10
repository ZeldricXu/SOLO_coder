import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { Checkbox, CheckboxGroup } from '@components/Checkbox';
import { createCheckboxGroupOptions } from './factories';
import React from 'react';

describe('Checkbox Component', () => {
  describe('基础渲染', () => {
    it('渲染默认复选框', () => {
      const { container } = render(<Checkbox label="同意协议" value="agree" />);
      expect(screen.getByLabelText('同意协议')).toBeInTheDocument();
      expect(container.querySelector('.wrapper')).toBeInTheDocument();
      expect(container.querySelector('.checkbox')).toBeInTheDocument();
      expect(container.querySelector('.input')).toBeInTheDocument();
      expect(container.querySelector('.label')).toBeInTheDocument();
    });

    it('渲染不带label的复选框', () => {
      render(<Checkbox value="test" aria-label="测试复选框" />);
      expect(screen.getByRole('checkbox', { name: '测试复选框' })).toBeInTheDocument();
    });

    it('默认尺寸为md', () => {
      const { container } = render(<Checkbox label="默认尺寸" value="test" />);
      const checkboxEl = container.querySelector('.checkbox');
      expect(checkboxEl).toHaveClass('md');
    });
  });

  describe('选中逻辑', () => {
    it('点击切换选中状态', () => {
      const handleChange = vi.fn();
      render(<Checkbox label="选择我" value="test" onChange={handleChange} />);

      const checkbox = screen.getByLabelText('选择我');
      expect(checkbox).not.toBeChecked();

      fireEvent.click(checkbox);
      expect(handleChange).toHaveBeenCalledTimes(1);
      expect(checkbox).toBeChecked();
    });

    it('受控模式checked正确', () => {
      const handleChange = vi.fn();
      const { rerender } = render(
        <Checkbox label="选择我" value="test" checked={true} onChange={handleChange} />,
      );

      const checkbox = screen.getByLabelText('选择我');
      expect(checkbox).toBeChecked();

      rerender(<Checkbox label="选择我" value="test" checked={false} onChange={handleChange} />);
      expect(checkbox).not.toBeChecked();
    });

    it('defaultValue设置默认选中', () => {
      render(<Checkbox label="默认选中" value="test" defaultChecked />);
      const checkbox = screen.getByLabelText('默认选中');
      expect(checkbox).toBeChecked();
    });
  });

  describe('半选状态(indeterminate)', () => {
    it('indeterminate状态显示半选', () => {
      render(<Checkbox label="全选" value="selectAll" indeterminate />);
      const checkbox = screen.getByLabelText('全选') as HTMLInputElement;
      expect(checkbox.indeterminate).toBe(true);
      expect(checkbox).toHaveAttribute('aria-checked', 'mixed');
    });

    it('indeterminate状态切换到全选', () => {
      const handleChange = vi.fn();
      const { rerender } = render(
        <Checkbox label="全选" value="selectAll" indeterminate checked={false} onChange={handleChange} />,
      );

      const checkbox = screen.getByLabelText('全选') as HTMLInputElement;
      expect(checkbox.indeterminate).toBe(true);

      rerender(
        <Checkbox label="全选" value="selectAll" indeterminate={false} checked={true} onChange={handleChange} />,
      );
      expect(checkbox).toBeChecked();
      expect(checkbox).toHaveAttribute('aria-checked', 'true');
      expect(checkbox.indeterminate).toBe(false);
    });
  });

  describe('状态测试', () => {
    it('disabled状态不可点击', () => {
      const handleChange = vi.fn();
      const { container } = render(<Checkbox label="禁用" value="test" disabled onChange={handleChange} />);

      const checkbox = screen.getByLabelText('禁用');
      expect(checkbox).toBeDisabled();

      const wrapper = container.querySelector('.wrapper');
      expect(wrapper).toHaveClass('disabled');

      fireEvent.click(checkbox);
      expect(handleChange).not.toHaveBeenCalled();
    });

    it('error状态显示错误样式', () => {
      const { container } = render(<Checkbox label="错误" value="test" error />);
      const checkbox = screen.getByLabelText('错误');
      expect(checkbox).toHaveAttribute('aria-invalid', 'true');

      const checkboxEl = container.querySelector('.checkbox');
      expect(checkboxEl).toHaveClass('error');
    });

    it('error字符串显示错误提示', () => {
      const { container } = render(<Checkbox label="错误" value="test" error="这是错误信息" />);
      expect(screen.getByText('这是错误信息')).toBeInTheDocument();
      expect(container.querySelector('.errorText')).toBeInTheDocument();
      expect(container.querySelector('.errorText')).toHaveAttribute('role', 'alert');
    });

    it('sm尺寸渲染正确', () => {
      const { container } = render(<Checkbox label="小尺寸" value="test" size="sm" />);
      const checkboxEl = container.querySelector('.checkbox');
      expect(checkboxEl).toHaveClass('sm');
      expect(container.querySelector('.label')).toHaveClass('smLabel');
    });

    it('lg尺寸渲染正确', () => {
      const { container } = render(<Checkbox label="大尺寸" value="test" size="lg" />);
      const checkboxEl = container.querySelector('.checkbox');
      expect(checkboxEl).toHaveClass('lg');
      expect(container.querySelector('.label')).toHaveClass('lgLabel');
    });

    it('md尺寸label正确', () => {
      const { container } = render(<Checkbox label="中尺寸" value="test" size="md" />);
      expect(container.querySelector('.label')).toHaveClass('mdLabel');
    });

    it('checkIcon和indeterminate元素存在', () => {
      const { container } = render(<Checkbox label="测试" value="test" />);
      expect(container.querySelector('.checkIcon')).toBeInTheDocument();
      expect(container.querySelector('.indeterminate')).toBeInTheDocument();
    });
  });

  describe('CheckboxGroup分组管理', () => {
    it('渲染分组选项', () => {
      const options = createCheckboxGroupOptions();
      const { container } = render(<CheckboxGroup options={options} name="test-group" />);

      expect(screen.getByLabelText('Option 1')).toBeInTheDocument();
      expect(screen.getByLabelText('Option 2')).toBeInTheDocument();
      expect(screen.getByLabelText('Option 3')).toBeInTheDocument();
      expect(container.querySelector('.group')).toBeInTheDocument();
    });

    it('分组label正确渲染', () => {
      const options = createCheckboxGroupOptions();
      const { container } = render(
        <CheckboxGroup options={options} name="test-group" label="请选择选项" />,
      );
      expect(screen.getByText('请选择选项')).toBeInTheDocument();
      expect(container.querySelector('.groupLabel')).toBeInTheDocument();
    });

    it('分组value控制选中', () => {
      const options = createCheckboxGroupOptions();
      const handleChange = vi.fn();
      render(
        <CheckboxGroup
          options={options}
          name="test-group"
          value={['opt1', 'opt3']}
          onChange={handleChange}
        />,
      );

      expect(screen.getByLabelText('Option 1')).toBeChecked();
      expect(screen.getByLabelText('Option 2')).not.toBeChecked();
      expect(screen.getByLabelText('Option 3')).toBeChecked();
    });

    it('点击选项触发group onChange', () => {
      const options = createCheckboxGroupOptions();
      const handleChange = vi.fn();
      render(
        <CheckboxGroup
          options={options}
          name="test-group"
          value={['opt1']}
          onChange={handleChange}
        />,
      );

      const option2 = screen.getByLabelText('Option 2');
      fireEvent.click(option2);

      expect(handleChange).toHaveBeenCalledWith(['opt1', 'opt2']);
    });

    it('取消选中触发group onChange', () => {
      const options = createCheckboxGroupOptions();
      const handleChange = vi.fn();
      render(
        <CheckboxGroup
          options={options}
          name="test-group"
          value={['opt1', 'opt2']}
          onChange={handleChange}
        />,
      );

      const option1 = screen.getByLabelText('Option 1');
      fireEvent.click(option1);

      expect(handleChange).toHaveBeenCalledWith(['opt2']);
    });

    it('disabled选项不可选中', () => {
      const options = createCheckboxGroupOptions();
      const handleChange = vi.fn();
      render(
        <CheckboxGroup
          options={options}
          name="test-group"
          value={['opt1']}
          onChange={handleChange}
        />,
      );

      const disabledOption = screen.getByLabelText('Option 4');
      expect(disabledOption).toBeDisabled();

      fireEvent.click(disabledOption);
      expect(handleChange).not.toHaveBeenCalled();
    });

    it('分组disabled状态所有选项禁用', () => {
      const options = createCheckboxGroupOptions();
      const handleChange = vi.fn();
      const { container } = render(
        <CheckboxGroup
          options={options}
          name="test-group"
          disabled
          onChange={handleChange}
        />,
      );

      options.forEach((opt) => {
        const checkbox = screen.getByLabelText(opt.label);
        expect(checkbox).toBeDisabled();
      });

      const wrappers = container.querySelectorAll('.wrapper');
      wrappers.forEach((wrapper) => {
        expect(wrapper).toHaveClass('disabled');
      });
    });

    it('横向排列正确', () => {
      const options = createCheckboxGroupOptions();
      const { container } = render(
        <CheckboxGroup options={options} name="test-group" orientation="horizontal" />,
      );
      expect(container.firstChild).toHaveClass('groupRow');
    });

    it('竖向排列正确', () => {
      const options = createCheckboxGroupOptions();
      const { container } = render(
        <CheckboxGroup options={options} name="test-group" orientation="vertical" />,
      );
      expect(container.firstChild).not.toHaveClass('groupRow');
      expect(container.firstChild).toHaveClass('group');
    });

    it('defaultValue设置默认选中', () => {
      const options = createCheckboxGroupOptions();
      render(
        <CheckboxGroup
          options={options}
          name="test-group"
          defaultValue={['opt2']}
        />,
      );

      expect(screen.getByLabelText('Option 1')).not.toBeChecked();
      expect(screen.getByLabelText('Option 2')).toBeChecked();
      expect(screen.getByLabelText('Option 3')).not.toBeChecked();
    });

    it('使用children方式渲染Checkbox', () => {
      const handleChange = vi.fn();
      render(
        <CheckboxGroup name="test-group" value={['child1']} onChange={handleChange}>
          <Checkbox label="子选项1" value="child1" />
          <Checkbox label="子选项2" value="child2" />
        </CheckboxGroup>,
      );

      expect(screen.getByLabelText('子选项1')).toBeChecked();
      expect(screen.getByLabelText('子选项2')).not.toBeChecked();

      fireEvent.click(screen.getByLabelText('子选项2'));
      expect(handleChange).toHaveBeenCalledWith(['child1', 'child2']);
    });

    it('使用children非受控模式', () => {
      render(
        <CheckboxGroup name="test-group" defaultValue={['child1']}>
          <Checkbox label="子选项1" value="child1" />
          <Checkbox label="子选项2" value="child2" />
        </CheckboxGroup>,
      );

      expect(screen.getByLabelText('子选项1')).toBeChecked();
      expect(screen.getByLabelText('子选项2')).not.toBeChecked();

      fireEvent.click(screen.getByLabelText('子选项2'));
      expect(screen.getByLabelText('子选项2')).toBeChecked();
      expect(screen.getByLabelText('子选项1')).toBeChecked();
    });
  });

  describe('ARIA无障碍属性', () => {
    it('复选框具有正确的ARIA属性', () => {
      render(<Checkbox label="测试" value="test" />);
      const checkbox = screen.getByRole('checkbox', { name: '测试' });
      expect(checkbox).toHaveAttribute('aria-checked', 'false');
    });

    it('选中状态aria-checked为true', () => {
      render(<Checkbox label="测试" value="test" checked />);
      const checkbox = screen.getByLabelText('测试');
      expect(checkbox).toHaveAttribute('aria-checked', 'true');
    });

    it('半选状态aria-checked为mixed', () => {
      render(<Checkbox label="测试" value="test" indeterminate />);
      const checkbox = screen.getByLabelText('测试');
      expect(checkbox).toHaveAttribute('aria-checked', 'mixed');
    });

    it('禁用状态aria-disabled为true', () => {
      render(<Checkbox label="测试" value="test" disabled />);
      const checkbox = screen.getByLabelText('测试');
      expect(checkbox).toHaveAttribute('aria-disabled', 'true');
    });

    it('分组具有正确的role', () => {
      const options = createCheckboxGroupOptions();
      const { container } = render(
        <CheckboxGroup options={options} name="test-group" label="选择项" />,
      );
      expect(container.querySelector('[role="group"]')).toBeInTheDocument();
    });
  });

  describe('键盘交互', () => {
    it('Space键切换选中', () => {
      const handleChange = vi.fn();
      render(<Checkbox label="测试" value="test" onChange={handleChange} />);

      const checkbox = screen.getByLabelText('测试');
      checkbox.focus();
      fireEvent.keyDown(checkbox, { key: ' ' });

      expect(handleChange).toHaveBeenCalled();
      expect(handleChange.mock.calls[0][0].target.checked).toBe(true);
      expect(handleChange.mock.calls[0][0].target.value).toBe('test');
    });

    it('Enter键切换选中', () => {
      const handleChange = vi.fn();
      render(<Checkbox label="测试" value="test" onChange={handleChange} />);

      const checkbox = screen.getByLabelText('测试');
      checkbox.focus();
      fireEvent.keyDown(checkbox, { key: 'Enter' });

      expect(handleChange).toHaveBeenCalled();
      expect(handleChange.mock.calls[0][0].target.checked).toBe(true);
      expect(handleChange.mock.calls[0][0].target.value).toBe('test');
    });

    it('disabled状态键盘事件不触发', () => {
      const handleChange = vi.fn();
      render(<Checkbox label="测试" value="test" disabled onChange={handleChange} />);

      const checkbox = screen.getByLabelText('测试');
      checkbox.focus();
      fireEvent.keyDown(checkbox, { key: ' ' });
      fireEvent.keyDown(checkbox, { key: 'Enter' });

      expect(handleChange).not.toHaveBeenCalled();
    });
  });

  describe('onChange事件对象', () => {
    it('点击时onChange事件包含正确的target属性', () => {
      const handleChange = vi.fn();
      render(<Checkbox label="测试" value="myValue" onChange={handleChange} />);

      const checkbox = screen.getByLabelText('测试');
      fireEvent.click(checkbox);

      expect(handleChange).toHaveBeenCalledTimes(1);
      const event = handleChange.mock.calls[0][0];
      expect(event.target.checked).toBe(true);
      expect(event.target.value).toBe('myValue');
    });

    it('取消选中时onChange事件正确', () => {
      const handleChange = vi.fn();
      render(<Checkbox label="测试" value="myValue" defaultChecked onChange={handleChange} />);

      const checkbox = screen.getByLabelText('测试');
      fireEvent.click(checkbox);

      expect(handleChange).toHaveBeenCalledTimes(1);
      const event = handleChange.mock.calls[0][0];
      expect(event.target.checked).toBe(false);
      expect(event.target.value).toBe('myValue');
    });
  });

  describe('className自定义', () => {
    it('支持自定义className', () => {
      const { container } = render(<Checkbox label="测试" value="test" className="custom-class" />);
      expect(container.querySelector('.wrapper')).toHaveClass('custom-class');
    });
  });
});
