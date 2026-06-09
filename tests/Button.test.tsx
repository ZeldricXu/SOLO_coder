import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { Button } from '@components/Button';
import React from 'react';

describe('Button Component', () => {
  it('renders correctly with default props', () => {
    render(<Button>Click me</Button>);
    const button = screen.getByRole('button', { name: /click me/i });
    expect(button).toBeInTheDocument();
    expect(button).not.toBeDisabled();
  });

  it('renders different variants correctly', () => {
    const { rerender } = render(<Button variant="primary">Primary</Button>);
    expect(screen.getByRole('button', { name: /primary/i })).toBeInTheDocument();

    rerender(<Button variant="secondary">Secondary</Button>);
    expect(screen.getByRole('button', { name: /secondary/i })).toBeInTheDocument();

    rerender(<Button variant="outline">Outline</Button>);
    expect(screen.getByRole('button', { name: /outline/i })).toBeInTheDocument();

    rerender(<Button variant="ghost">Ghost</Button>);
    expect(screen.getByRole('button', { name: /ghost/i })).toBeInTheDocument();

    rerender(<Button variant="danger">Danger</Button>);
    expect(screen.getByRole('button', { name: /danger/i })).toBeInTheDocument();
  });

  it('renders different sizes correctly', () => {
    const { rerender } = render(<Button size="sm">Small</Button>);
    expect(screen.getByRole('button', { name: /small/i })).toBeInTheDocument();

    rerender(<Button size="md">Medium</Button>);
    expect(screen.getByRole('button', { name: /medium/i })).toBeInTheDocument();

    rerender(<Button size="lg">Large</Button>);
    expect(screen.getByRole('button', { name: /large/i })).toBeInTheDocument();
  });

  it('handles disabled state correctly', () => {
    const handleClick = vi.fn();
    render(<Button disabled onClick={handleClick}>Disabled</Button>);
    const button = screen.getByRole('button', { name: /disabled/i });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('aria-disabled', 'true');
    fireEvent.click(button);
    expect(handleClick).not.toHaveBeenCalled();
  });

  it('handles loading state correctly', () => {
    const handleClick = vi.fn();
    render(<Button loading onClick={handleClick}>Loading</Button>);
    const button = screen.getByRole('button');
    expect(button).toBeDisabled();
    fireEvent.click(button);
    expect(handleClick).not.toHaveBeenCalled();
  });

  it('calls onClick handler when clicked', () => {
    const handleClick = vi.fn();
    render(<Button onClick={handleClick}>Click me</Button>);
    const button = screen.getByRole('button', { name: /click me/i });
    fireEvent.click(button);
    expect(handleClick).toHaveBeenCalledTimes(1);
  });

  it('handles keyboard events correctly', () => {
    const handleClick = vi.fn();
    render(<Button onClick={handleClick}>Click me</Button>);
    const button = screen.getByRole('button', { name: /click me/i });

    fireEvent.keyDown(button, { key: 'Enter' });
    expect(handleClick).toHaveBeenCalledTimes(1);

    fireEvent.keyDown(button, { key: ' ' });
    expect(handleClick).toHaveBeenCalledTimes(2);
  });

  it('renders with fullWidth prop', () => {
    render(<Button fullWidth>Full Width</Button>);
    const button = screen.getByRole('button', { name: /full width/i });
    expect(button).toBeInTheDocument();
  });

  it('has correct ARIA attributes', () => {
    render(<Button aria-label="Custom label">Click me</Button>);
    const button = screen.getByRole('button', { name: /custom label/i });
    expect(button).toHaveAttribute('role', 'button');
    expect(button).toHaveAttribute('tabindex', '0');
  });
});
