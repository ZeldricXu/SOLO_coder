import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act, cleanup } from '@testing-library/react';
import { Modal, confirm } from '@components/Modal';
import { Button } from '@components/Button';
import React from 'react';

vi.useFakeTimers();

describe('Modal Component', () => {
  afterEach(() => {
    vi.clearAllTimers();
    cleanup();
    const portals = document.querySelectorAll('[data-testid="modal-mask"]');
    portals.forEach(portal => {
      if (portal.parentNode) {
        portal.parentNode.removeChild(portal);
      }
    });
  });

  describe('基础渲染', () => {
    it('open=true时渲染弹窗', () => {
      render(<Modal open title="测试弹窗" onClose={vi.fn()}>内容</Modal>);
      expect(screen.getByRole('dialog')).toBeInTheDocument();
      expect(screen.getByText('测试弹窗')).toBeInTheDocument();
      expect(screen.getByText('内容')).toBeInTheDocument();
    });

    it('open=false时不渲染弹窗', () => {
      render(<Modal open={false} title="测试弹窗" onClose={vi.fn()}>内容</Modal>);
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });

    it('渲染到body的Portal', () => {
      render(
        <div data-testid="app-root">
          <Modal open title="测试弹窗" onClose={vi.fn()}>内容</Modal>
        </div>,
      );

      const appRoot = screen.getByTestId('app-root');
      const dialog = screen.getByRole('dialog');

      expect(appRoot.contains(dialog)).toBe(false);
      expect(document.body.contains(dialog)).toBe(true);
    });
  });

  describe('关闭逻辑', () => {
    it('点击右上角关闭按钮关闭', async () => {
      const handleClose = vi.fn();
      render(<Modal open title="测试" onClose={handleClose}>内容</Modal>);

      const closeBtn = screen.getByRole('button', { name: /关闭/i });
      fireEvent.click(closeBtn);

      expect(handleClose).toHaveBeenCalledTimes(1);
    });

    it('点击遮罩关闭(maskClosable=true)', () => {
      const handleClose = vi.fn();
      render(<Modal open title="测试" onClose={handleClose} maskClosable>内容</Modal>);

      const mask = screen.getByTestId('modal-mask');
      fireEvent.click(mask);

      expect(handleClose).toHaveBeenCalledTimes(1);
    });

    it('点击遮罩不关闭(maskClosable=false)', () => {
      const handleClose = vi.fn();
      render(<Modal open title="测试" onClose={handleClose} maskClosable={false}>内容</Modal>);

      const mask = screen.getByTestId('modal-mask');
      fireEvent.click(mask);

      expect(handleClose).not.toHaveBeenCalled();
    });

    it('点击确定按钮调用onOk并关闭', () => {
      const handleOk = vi.fn();
      const handleClose = vi.fn();
      render(
        <Modal open title="测试" onClose={handleClose} onOk={handleOk}>
          内容
        </Modal>,
      );

      const okBtn = screen.getByRole('button', { name: /确定/i });
      fireEvent.click(okBtn);

      expect(handleOk).toHaveBeenCalledTimes(1);
    });

    it('点击取消按钮调用onCancel并关闭', () => {
      const handleCancel = vi.fn();
      const handleClose = vi.fn();
      render(
        <Modal open title="测试" onClose={handleClose} onCancel={handleCancel}>
          内容
        </Modal>,
      );

      const cancelBtn = screen.getByRole('button', { name: /取消/i });
      fireEvent.click(cancelBtn);

      expect(handleCancel).toHaveBeenCalledTimes(1);
      expect(handleClose).toHaveBeenCalledTimes(1);
    });

    it('onOk返回Promise时显示loading', async () => {
      vi.useRealTimers();
      
      let resolvePromise: (value: void | PromiseLike<void>) => void;
      const handleOk = vi.fn().mockReturnValue(new Promise<void>((resolve) => {
        resolvePromise = resolve;
      }));

      render(
        <Modal open title="测试" onClose={vi.fn()} onOk={handleOk}>
          内容
        </Modal>,
      );

      const okBtn = screen.getByRole('button', { name: /确定/i });
      fireEvent.click(okBtn);

      await waitFor(() => {
        expect(okBtn).toBeDisabled();
        expect(okBtn).toHaveAttribute('aria-busy', 'true');
      });

      act(() => {
        resolvePromise();
      });

      await waitFor(() => {
        expect(okBtn).not.toBeDisabled();
      });
      
      vi.useFakeTimers();
    });
  });

  describe('异常场景 - Escape键关闭', () => {
    it('打开状态下按Escape关闭', () => {
      const handleClose = vi.fn();
      render(<Modal open title="测试" onClose={handleClose}>内容</Modal>);

      fireEvent.keyDown(document, { key: 'Escape' });
      expect(handleClose).toHaveBeenCalledTimes(1);
    });

    it('关闭状态下按Escape无反应', () => {
      const handleClose = vi.fn();
      render(<Modal open={false} title="测试" onClose={handleClose}>内容</Modal>);

      fireEvent.keyDown(document, { key: 'Escape' });
      expect(handleClose).not.toHaveBeenCalled();
    });

    it('escapable=false时Escape不关闭', () => {
      const handleClose = vi.fn();
      render(<Modal open title="测试" onClose={handleClose} escapable={false}>内容</Modal>);

      fireEvent.keyDown(document, { key: 'Escape' });
      expect(handleClose).not.toHaveBeenCalled();
    });
  });

  describe('焦点管理', () => {
    it('打开时焦点在弹窗内', () => {
      vi.useRealTimers();
      
      render(
        <Modal open title="测试" onClose={vi.fn()}>
          <Button>按钮1</Button>
          <Button>按钮2</Button>
        </Modal>,
      );

      const dialog = screen.getByRole('dialog');
      dialog.focus();
      expect(document.activeElement).toBe(dialog);
      
      vi.useFakeTimers();
    });

    it('Tab键在弹窗内循环', () => {
      vi.useRealTimers();
      
      render(
        <Modal open title="测试" onClose={vi.fn()}>
          <Button>按钮1</Button>
          <Button>按钮2</Button>
        </Modal>,
      );

      const btn1 = screen.getByRole('button', { name: '按钮1' });
      const btn2 = screen.getByRole('button', { name: '按钮2' });
      const closeBtn = screen.getByRole('button', { name: /关闭/i });
      const okBtn = screen.getByRole('button', { name: /确定/i });
      const cancelBtn = screen.getByRole('button', { name: /取消/i });

      closeBtn.focus();
      expect(closeBtn).toHaveFocus();

      fireEvent.keyDown(closeBtn, { key: 'Tab' });
      okBtn.focus();
      expect(okBtn).toHaveFocus();

      fireEvent.keyDown(okBtn, { key: 'Tab' });
      cancelBtn.focus();
      expect(cancelBtn).toHaveFocus();

      fireEvent.keyDown(cancelBtn, { key: 'Tab' });
      btn1.focus();
      expect(btn1).toHaveFocus();

      fireEvent.keyDown(btn1, { key: 'Tab' });
      btn2.focus();
      expect(btn2).toHaveFocus();

      vi.useFakeTimers();
    });

    it('Shift+Tab反向循环', () => {
      vi.useRealTimers();
      
      render(
        <Modal open title="测试" onClose={vi.fn()}>
          <Button>按钮1</Button>
        </Modal>,
      );

      const closeBtn = screen.getByRole('button', { name: /关闭/i });
      const btn1 = screen.getByRole('button', { name: '按钮1' });

      btn1.focus();
      expect(btn1).toHaveFocus();

      fireEvent.keyDown(btn1, { key: 'Tab', shiftKey: true });
      closeBtn.focus();
      expect(closeBtn).toHaveFocus();

      vi.useFakeTimers();
    });
  });

  describe('confirm函数式调用', () => {
    it('confirm.info显示信息弹窗', () => {
      act(() => {
        confirm.info({ title: '信息', content: '这是一条信息' });
      });

      const dialog = document.body.querySelector('[role="alertdialog"]');
      expect(dialog).toBeInTheDocument();
      expect(dialog?.textContent).toContain('信息');
      expect(dialog?.textContent).toContain('这是一条信息');
    });

    it('confirm.success显示成功弹窗', () => {
      act(() => {
        confirm.success({ title: '成功', content: '操作成功' });
      });

      const dialog = document.body.querySelector('[role="alertdialog"]');
      expect(dialog).toBeInTheDocument();
      const icon = document.body.querySelector('[data-testid="success-icon"]');
      expect(icon).toBeInTheDocument();
    });

    it('confirm.warning显示警告弹窗', () => {
      act(() => {
        confirm.warning({ title: '警告', content: '请注意' });
      });

      const dialog = document.body.querySelector('[role="alertdialog"]');
      expect(dialog).toBeInTheDocument();
      expect(document.body.querySelector('[data-testid="warning-icon"]')).toBeInTheDocument();
    });

    it('confirm.error显示错误弹窗', () => {
      act(() => {
        confirm.error({ title: '错误', content: '操作失败' });
      });

      const dialog = document.body.querySelector('[role="alertdialog"]');
      expect(dialog).toBeInTheDocument();
      expect(document.body.querySelector('[data-testid="error-icon"]')).toBeInTheDocument();
    });

    it('confirm.confirm显示确认弹窗', () => {
      const onOk = vi.fn();
      act(() => {
        confirm.confirm({ title: '确认', content: '确定要删除吗？', onOk });
      });

      const dialog = document.body.querySelector('[role="alertdialog"]');
      expect(dialog).toBeInTheDocument();
      expect(document.body.querySelector('[data-testid="confirm-icon"]')).toBeInTheDocument();

      const buttons = document.body.querySelectorAll('button');
      const okBtn = Array.from(buttons).find(btn => btn.textContent?.includes('确定'));
      if (okBtn) {
        fireEvent.click(okBtn);
      }

      expect(onOk).toHaveBeenCalled();
    });

    it('confirm点击取消调用onCancel', () => {
      const onCancel = vi.fn();
      act(() => {
        confirm.confirm({ title: '确认', content: '确定吗？', onCancel });
      });

      const dialog = document.body.querySelector('[role="alertdialog"]');
      expect(dialog).toBeInTheDocument();

      const buttons = document.body.querySelectorAll('button');
      const cancelBtn = Array.from(buttons).find(btn => btn.textContent?.includes('取消'));
      if (cancelBtn) {
        fireEvent.click(cancelBtn);
      }

      expect(onCancel).toHaveBeenCalled();
    });
  });

  describe('ARIA无障碍属性', () => {
    it('弹窗具有正确的role和aria-modal', () => {
      render(<Modal open title="测试弹窗" onClose={vi.fn()}>内容</Modal>);

      const dialog = screen.getByRole('dialog');
      expect(dialog).toHaveAttribute('role', 'dialog');
      expect(dialog).toHaveAttribute('aria-modal', 'true');
      expect(dialog).toHaveAttribute('aria-labelledby');
    });

    it('confirm弹窗使用alertdialog role', () => {
      act(() => {
        confirm.confirm({ title: '确认', content: '内容' });
      });

      const dialog = document.body.querySelector('[role="alertdialog"]');
      expect(dialog).toBeInTheDocument();
    });

    it('标题与弹窗关联', () => {
      render(<Modal open title="测试弹窗" onClose={vi.fn()}>内容</Modal>);

      const dialog = screen.getByRole('dialog');
      const title = screen.getByText('测试弹窗');
      expect(dialog).toHaveAttribute('aria-labelledby', title.id);
    });

    it('内容与弹窗关联', () => {
      render(<Modal open title="测试" onClose={vi.fn()}>弹窗内容</Modal>);

      const dialog = screen.getByRole('dialog');
      const content = screen.getByText('弹窗内容');
      expect(dialog).toHaveAttribute('aria-describedby', content.id);
    });
  });

  describe('样式和布局', () => {
    it('centered居中显示', () => {
      render(
        <Modal open title="测试" onClose={vi.fn()} centered>内容</Modal>,
      );
      const overlay = document.body.querySelector('.overlay');
      expect(overlay).toHaveClass('overlayCentered');
    });

    it('不同尺寸渲染正确', () => {
      const { rerender } = render(
        <Modal open title="测试" onClose={vi.fn()} width={400}>内容</Modal>,
      );
      const modal = document.body.querySelector('.modal');
      expect(modal).toHaveStyle({ width: '400px' });

      rerender(<Modal open title="测试" onClose={vi.fn()} width={800}>内容</Modal>);
      expect(modal).toHaveStyle({ width: '800px' });
    });

    it('自定义footer', () => {
      render(
        <Modal
          open
          title="测试"
          onClose={vi.fn()}
          footer={<Button variant="primary">自定义按钮</Button>}
        >
          内容
        </Modal>,
      );

      expect(screen.getByRole('button', { name: '自定义按钮' })).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /确定/i })).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /取消/i })).not.toBeInTheDocument();
    });

    it('footer=null不显示底部', () => {
      render(<Modal open title="测试" onClose={vi.fn()} footer={null}>内容</Modal>);
      expect(screen.queryByRole('button', { name: /确定/i })).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /取消/i })).not.toBeInTheDocument();
    });
  });
});
