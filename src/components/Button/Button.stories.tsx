import type { Meta, StoryObj } from '@storybook/react';
import { userEvent, within, expect, fn } from '@storybook/test';
import { Button } from './index';
import React from 'react';

const meta: Meta<typeof Button> = {
  title: 'Components/Button',
  component: Button,
  tags: ['autodocs'],
  argTypes: {
    variant: {
      control: { type: 'select' },
      options: ['primary', 'secondary', 'outline', 'ghost', 'danger'],
    },
    size: {
      control: { type: 'select' },
      options: ['sm', 'md', 'lg'],
    },
    onClick: { action: 'clicked' },
  },
  args: {
    children: 'Button',
    onClick: fn(),
  },
};

export default meta;
type Story = StoryObj<typeof Button>;

export const Primary: Story = {
  args: {
    variant: 'primary',
    children: 'Primary Button',
  },
  play: async ({ canvasElement, args }) => {
    const canvas = within(canvasElement);
    const button = canvas.getByRole('button', { name: /primary button/i });
    await userEvent.click(button);
    expect(args.onClick).toHaveBeenCalled();
  },
};

export const Secondary: Story = {
  args: {
    variant: 'secondary',
    children: 'Secondary Button',
  },
};

export const Outline: Story = {
  args: {
    variant: 'outline',
    children: 'Outline Button',
  },
};

export const Ghost: Story = {
  args: {
    variant: 'ghost',
    children: 'Ghost Button',
  },
};

export const Danger: Story = {
  args: {
    variant: 'danger',
    children: 'Danger Button',
  },
};

export const Small: Story = {
  args: {
    size: 'sm',
    children: 'Small Button',
  },
};

export const Medium: Story = {
  args: {
    size: 'md',
    children: 'Medium Button',
  },
};

export const Large: Story = {
  args: {
    size: 'lg',
    children: 'Large Button',
  },
};

export const Disabled: Story = {
  args: {
    disabled: true,
    children: 'Disabled Button',
  },
  play: async ({ canvasElement, args }) => {
    const canvas = within(canvasElement);
    const button = canvas.getByRole('button', { name: /disabled button/i });
    expect(button).toBeDisabled();
    await userEvent.click(button);
    expect(args.onClick).not.toHaveBeenCalled();
  },
};

export const Loading: Story = {
  args: {
    loading: true,
    children: 'Loading Button',
  },
};

export const FullWidth: Story = {
  args: {
    fullWidth: true,
    children: 'Full Width Button',
  },
};

export const WithIcon: Story = {
  args: {
    leftIcon: (
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
        <path d="M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z" />
      </svg>
    ),
    children: 'With Left Icon',
  },
};

export const KeyboardInteraction: Story = {
  args: {
    children: 'Keyboard Test Button',
  },
  play: async ({ canvasElement, args }) => {
    const canvas = within(canvasElement);
    const button = canvas.getByRole('button', { name: /keyboard test button/i });
    await button.focus();
    expect(button).toHaveFocus();
    await userEvent.keyboard('{Enter}');
    expect(args.onClick).toHaveBeenCalledTimes(1);
    await userEvent.keyboard(' ');
    expect(args.onClick).toHaveBeenCalledTimes(2);
  },
};
