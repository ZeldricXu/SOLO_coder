import type { Meta, StoryObj } from '@storybook/react';
import { userEvent, within, expect, fn } from '@storybook/test';
import { Tabs, TabPane } from './index';
import React from 'react';

const meta: Meta<typeof Tabs> = {
  title: 'Components/Tabs',
  component: Tabs,
  tags: ['autodocs'],
  argTypes: {
    variant: {
      control: { type: 'select' },
      options: ['line', 'card', 'capsule'],
    },
    size: {
      control: { type: 'select' },
      options: ['sm', 'md', 'lg'],
    },
    placement: {
      control: { type: 'select' },
      options: ['top', 'bottom', 'left', 'right'],
    },
    onChange: { action: 'changed' },
  },
  args: {
    items: [
      { key: 'tab1', label: 'Tab 1' },
      { key: 'tab2', label: 'Tab 2' },
      { key: 'tab3', label: 'Tab 3' },
    ],
    onChange: fn(),
    children: (
      <>
        <TabPane tabKey="tab1">Content of Tab 1</TabPane>
        <TabPane tabKey="tab2">Content of Tab 2</TabPane>
        <TabPane tabKey="tab3">Content of Tab 3</TabPane>
      </>
    ),
  },
};

export default meta;
type Story = StoryObj<typeof Tabs>;

export const Default: Story = {};

export const LineVariant: Story = {
  args: {
    variant: 'line',
  },
  play: async ({ canvasElement, args }) => {
    const canvas = within(canvasElement);
    const tab2 = canvas.getByRole('tab', { name: /tab 2/i });
    await userEvent.click(tab2);
    expect(args.onChange).toHaveBeenCalledWith('tab2');
    expect(canvas.getByText('Content of Tab 2')).toBeVisible();
  },
};

export const CardVariant: Story = {
  args: {
    variant: 'card',
  },
};

export const CapsuleVariant: Story = {
  args: {
    variant: 'capsule',
  },
};

export const Small: Story = {
  args: {
    size: 'sm',
  },
};

export const Large: Story = {
  args: {
    size: 'lg',
  },
};

export const LeftPlacement: Story = {
  args: {
    placement: 'left',
    style: { minHeight: '200px' },
  },
};

export const RightPlacement: Story = {
  args: {
    placement: 'right',
    style: { minHeight: '200px' },
  },
};

export const WithDisabledTab: Story = {
  args: {
    items: [
      { key: 'tab1', label: 'Tab 1' },
      { key: 'tab2', label: 'Disabled', disabled: true },
      { key: 'tab3', label: 'Tab 3' },
    ],
  },
  play: async ({ canvasElement, args }) => {
    const canvas = within(canvasElement);
    const disabledTab = canvas.getByRole('tab', { name: /disabled/i });
    expect(disabledTab).toHaveAttribute('aria-disabled', 'true');
    await userEvent.click(disabledTab);
    expect(args.onChange).not.toHaveBeenCalled();
  },
};

export const KeyboardNavigation: Story = {
  args: {
    items: [
      { key: 'tab1', label: 'First' },
      { key: 'tab2', label: 'Second' },
      { key: 'tab3', label: 'Third' },
    ],
  },
  play: async ({ canvasElement, args }) => {
    const canvas = within(canvasElement);
    const firstTab = canvas.getByRole('tab', { name: /first/i });
    const secondTab = canvas.getByRole('tab', { name: /second/i });

    await firstTab.focus();
    expect(firstTab).toHaveFocus();

    await userEvent.keyboard('{ArrowRight}');
    expect(secondTab).toHaveFocus();

    await userEvent.keyboard('{Enter}');
    expect(args.onChange).toHaveBeenCalledWith('tab2');

    await userEvent.keyboard('{Home}');
    expect(firstTab).toHaveFocus();

    await userEvent.keyboard('{End}');
    const thirdTab = canvas.getByRole('tab', { name: /third/i });
    expect(thirdTab).toHaveFocus();
  },
};
