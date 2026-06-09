import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { Input } from '@components/Input';
import React from 'react';

describe('Input Component', () => {
  it('renders correctly with default props', () => {
    render(<Input placeholder="Enter text" />);
    const input = screen.getByPlaceholderText('Enter text');
    expect(input).toBeInTheDocument();
    expect(input).not.toBeDisabled();
  });

  it('renders with label correctly', () => {
    render(<Input label="Username" placeholder="Enter username" />);
    expect(screen.getByText('Username')).toBeInTheDocument();
    expect(screen.getByPlaceholderText('Enter username')).toBeInTheDocument();
  });

  it('renders with helper text correctly', () => {
    render(<Input helperText="Max 20 characters" placeholder="Enter text" />);
    expect(screen.getByText('Max 20 characters')).toBeInTheDocument();
  });

  it('renders with error state correctly', () => {
    render(<Input error="This field is required" placeholder="Enter text" />);
    const input = screen.getByPlaceholderText('Enter text');
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByText('This field is required')).toBeInTheDocument();
  });

  it('handles disabled state correctly', () => {
    render(<Input disabled placeholder="Enter text" />);
    const input = screen.getByPlaceholderText('Enter text');
    expect(input).toBeDisabled();
    expect(input).toHaveAttribute('aria-disabled', 'true');
  });

  it('handles controlled value correctly', () => {
    const handleChange = vi.fn();
    render(<Input value="test" onChange={handleChange} />);
    const input = screen.getByRole('textbox') as HTMLInputElement;
    expect(input.value).toBe('test');

    fireEvent.change(input, { target: { value: 'new value' } });
    expect(handleChange).toHaveBeenCalledTimes(1);
  });

  it('handles defaultValue correctly', () => {
    render(<Input defaultValue="default" />);
    const input = screen.getByRole('textbox') as HTMLInputElement;
    expect(input.value).toBe('default');
  });

  it('renders different sizes correctly', () => {
    const { rerender } = render(<Input size="sm" placeholder="Small" />);
    expect(screen.getByPlaceholderText('Small')).toBeInTheDocument();

    rerender(<Input size="md" placeholder="Medium" />);
    expect(screen.getByPlaceholderText('Medium')).toBeInTheDocument();

    rerender(<Input size="lg" placeholder="Large" />);
    expect(screen.getByPlaceholderText('Large')).toBeInTheDocument();
  });

  it('renders with prefix and suffix correctly', () => {
    render(
      <Input
        prefix={<span data-testid="prefix">https://</span>}
        suffix={<span data-testid="suffix">.com</span>}
        placeholder="Enter domain"
      />,
    );
    expect(screen.getByTestId('prefix')).toBeInTheDocument();
    expect(screen.getByTestId('suffix')).toBeInTheDocument();
  });

  it('renders with showCount correctly', () => {
    render(<Input showCount maxLength={20} placeholder="Enter text" />);
    const input = screen.getByPlaceholderText('Enter text');
    fireEvent.change(input, { target: { value: 'test' } });
    expect(screen.getByText('4/20')).toBeInTheDocument();
  });

  it('has correct ARIA attributes', () => {
    render(
      <Input
        label="Email"
        required
        error="Invalid email"
        placeholder="Enter email"
      />,
    );
    const input = screen.getByPlaceholderText('Enter email');
    expect(input).toHaveAttribute('aria-required', 'true');
    expect(input).toHaveAttribute('aria-invalid', 'true');
  });
});
