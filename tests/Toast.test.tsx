import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import { ToastProvider, useToast } from '@components/Toast';
import { createToastOptions, createMultipleToasts } from './factories';
import React from 'react';

const TestComponent: React.FC<{ onReady?: (toast: ReturnType<typeof useToast>) => void }> = ({ onReady }) => {
  const toast = useToast();
  onReady?.(toast);
  return <div data-testid="test-component">Test</div>;
};

describe('Toast Component', () => {

  describe('基础渲染', () => {
    it('ToastProvider渲染正确', () => {
      render(
        <ToastProvider>
          <div>内容</div>
        </ToastProvider>,
      );
      expect(screen.getByText('内容')).toBeInTheDocument();
    });

    it('useToast必须在ToastProvider内使用', () => {
      const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
      expect(() => render(<TestComponent />)).toThrow();
      consoleError.mockRestore();
    });
  });

  describe('Toast显示', () => {
    it('toast方法显示Toast', async () => {
      let toastApi: ReturnType<typeof useToast> | null = null;

      render(
        <ToastProvider>
          <TestComponent onReady={(api) => (toastApi = api)} />
        </ToastProvider>,
      );

      act(() => {
        toastApi?.toast(createToastOptions({ message: '测试消息', type: 'success' }));
      });

      await waitFor(() => {
        expect(screen.getByText('测试消息')).toBeInTheDocument();
      });
    });

    it('不同类型Toast显示正确样式', async () => {
      let toastApi: ReturnType<typeof useToast> | null = null;
      const types = ['info', 'success', 'warning', 'error', 'default'] as const;

      render(
        <ToastProvider>
          <TestComponent onReady={(api) => (toastApi = api)} />
        </ToastProvider>,
      );

      for (const type of types) {
        act(() => {
          toastApi?.toast(createToastOptions({ message: `${type}消息`, type, id: `toast-${type}` }));
        });

        await waitFor(() => {
          const toast = screen.getByText(`${type}消息`).closest('.toast');
          expect(toast).toBeInTheDocument();
          const iconEl = toast?.querySelector('.icon');
          expect(iconEl).toHaveClass(type);
        });
      }
    });
  });

  describe('多实例堆叠', () => {
    it('同时弹出多个时正确堆叠不重叠', async () => {
      let toastApi: ReturnType<typeof useToast> | null = null;
      const toasts = createMultipleToasts(5);

      render(
        <ToastProvider>
          <TestComponent onReady={(api) => (toastApi = api)} />
        </ToastProvider>,
      );

      act(() => {
        toasts.forEach((t) => toastApi?.toast(t));
      });

      await waitFor(() => {
        expect(screen.getAllByRole('status')).toHaveLength(5);
      });

      const container = document.querySelector('.container');
      expect(container).toHaveClass('topRight');
      expect(container).toBeInTheDocument();
      expect(container).toHaveClass('container');

      const toastElements = screen.getAllByRole('status');
      expect(toastElements).toHaveLength(5);

      toastElements.forEach((toast) => {
        expect(toast).toHaveClass('toast');
      });

      const containerChildren = container?.children;
      expect(containerChildren?.length).toBe(5);

      for (let i = 0; i < (containerChildren?.length || 0); i++) {
        expect(containerChildren?.[i]).toHaveClass('toast');
      }
    });

    it('按位置分组渲染', async () => {
      let toastApi: ReturnType<typeof useToast> | null = null;
      const positions = ['top-left', 'top-right', 'bottom-left', 'bottom-right'] as const;
      const positionClassMap: Record<string, string> = {
        'top-left': 'topLeft',
        'top-right': 'topRight',
        'bottom-left': 'bottomLeft',
        'bottom-right': 'bottomRight',
      };

      render(
        <ToastProvider>
          <TestComponent onReady={(api) => (toastApi = api)} />
        </ToastProvider>,
      );

      act(() => {
        positions.forEach((pos, i) => {
          toastApi?.toast({
            id: `toast-${pos}`,
            message: `${pos}消息`,
            type: 'info',
            position: pos,
          });
        });
      });

      await waitFor(() => {
        positions.forEach((pos) => {
          expect(screen.getByText(`${pos}消息`)).toBeInTheDocument();
        });
      });

      const containers = document.querySelectorAll('.container');
      expect(containers).toHaveLength(positions.length);

      positions.forEach((pos) => {
        const expectedClass = positionClassMap[pos];
        const container = document.querySelector(`.${expectedClass}`);
        expect(container).toBeInTheDocument();
        expect(container).toHaveClass('container');

        const toastInContainer = container?.querySelector('.toast');
        expect(toastInContainer).toBeInTheDocument();
        expect(toastInContainer?.textContent).toContain(`${pos}消息`);
      });
    });

    it('超过最大数量时限制显示', async () => {
      let toastApi: ReturnType<typeof useToast> | null = null;
      const toasts = createMultipleToasts(15);

      render(
        <ToastProvider limit={10}>
          <TestComponent onReady={(api) => (toastApi = api)} />
        </ToastProvider>,
      );

      act(() => {
        toasts.forEach((t) => toastApi?.toast(t));
      });

      await waitFor(() => {
        expect(screen.getAllByRole('status')).toHaveLength(10);
      });
    });
  });

  describe('关闭逻辑', () => {
    it('duration后自动关闭', async () => {
      let toastApi: ReturnType<typeof useToast> | null = null;

      render(
        <ToastProvider>
          <TestComponent onReady={(api) => (toastApi = api)} />
        </ToastProvider>,
      );

      act(() => {
        toastApi?.toast(createToastOptions({ message: '自动关闭', duration: 50 }));
      });

      await waitFor(() => {
        expect(screen.getByText('自动关闭')).toBeInTheDocument();
      });

      await waitFor(() => {
        expect(screen.queryByText('自动关闭')).not.toBeInTheDocument();
      }, { timeout: 5000 });
    });

    it('点击关闭按钮手动关闭', async () => {
      let toastApi: ReturnType<typeof useToast> | null = null;

      render(
        <ToastProvider>
          <TestComponent onReady={(api) => (toastApi = api)} />
        </ToastProvider>,
      );

      act(() => {
        toastApi?.toast(createToastOptions({ message: '手动关闭', duration: Infinity }));
      });

      await waitFor(() => {
        expect(screen.getByText('手动关闭')).toBeInTheDocument();
      });

      const closeBtn = screen.getByRole('button', { name: /关闭通知/i });
      fireEvent.click(closeBtn);

      await waitFor(() => {
        expect(screen.queryByText('手动关闭')).not.toBeInTheDocument();
      }, { timeout: 1000 });
    });

    it('dismiss方法移除指定Toast', async () => {
      let toastApi: ReturnType<typeof useToast> | null = null;

      render(
        <ToastProvider>
          <TestComponent onReady={(api) => (toastApi = api)} />
        </ToastProvider>,
      );

      act(() => {
        toastApi?.toast(createToastOptions({ id: 'toast-1', message: 'Toast 1', duration: Infinity }));
        toastApi?.toast(createToastOptions({ id: 'toast-2', message: 'Toast 2', duration: Infinity }));
      });

      await waitFor(() => {
        expect(screen.getByText('Toast 1')).toBeInTheDocument();
        expect(screen.getByText('Toast 2')).toBeInTheDocument();
      });

      act(() => {
        toastApi?.dismiss('toast-1');
      });

      await waitFor(() => {
        expect(screen.queryByText('Toast 1')).not.toBeInTheDocument();
        expect(screen.getByText('Toast 2')).toBeInTheDocument();
      }, { timeout: 1000 });
    });

    it('dismissAll方法移除所有Toast', async () => {
      let toastApi: ReturnType<typeof useToast> | null = null;
      const toasts = createMultipleToasts(5).map(t => ({ ...t, duration: Infinity }));

      render(
        <ToastProvider>
          <TestComponent onReady={(api) => (toastApi = api)} />
        </ToastProvider>,
      );

      act(() => {
        toasts.forEach((t) => toastApi?.toast(t));
      });

      await waitFor(() => {
        expect(screen.getAllByRole('status')).toHaveLength(5);
      });

      act(() => {
        toastApi?.dismissAll();
      });

      await waitFor(() => {
        expect(screen.queryAllByRole('status')).toHaveLength(0);
      }, { timeout: 1000 });
    });
  });

  describe('操作按钮', () => {
    it('渲染action按钮', async () => {
      let toastApi: ReturnType<typeof useToast> | null = null;
      const handleAction = vi.fn();

      render(
        <ToastProvider>
          <TestComponent onReady={(api) => (toastApi = api)} />
        </ToastProvider>,
      );

      act(() => {
        toastApi?.toast(
          createToastOptions({
            message: '有操作按钮',
            action: { label: '撤销', onClick: handleAction },
          }),
        );
      });

      await waitFor(() => {
        expect(screen.getByRole('button', { name: '撤销' })).toBeInTheDocument();
      });

      fireEvent.click(screen.getByRole('button', { name: '撤销' }));
      expect(handleAction).toHaveBeenCalled();
    });
  });

  describe('ARIA无障碍属性', () => {
    it('Toast具有正确的role和aria-live', async () => {
      let toastApi: ReturnType<typeof useToast> | null = null;

      render(
        <ToastProvider>
          <TestComponent onReady={(api) => (toastApi = api)} />
        </ToastProvider>,
      );

      act(() => {
        toastApi?.toast(createToastOptions({ message: '无障碍测试' }));
      });

      await waitFor(() => {
        const toast = screen.getByRole('status');
        expect(toast).toHaveAttribute('role', 'status');
        expect(toast).toHaveAttribute('aria-live', 'polite');
      });
    });

    it('error类型使用assertive live region', async () => {
      let toastApi: ReturnType<typeof useToast> | null = null;

      render(
        <ToastProvider>
          <TestComponent onReady={(api) => (toastApi = api)} />
        </ToastProvider>,
      );

      act(() => {
        toastApi?.toast(createToastOptions({ message: '错误消息', type: 'error' }));
      });

      await waitFor(() => {
        const toast = screen.getByRole('status');
        expect(toast).toHaveAttribute('aria-live', 'assertive');
      });
    });
  });
});
