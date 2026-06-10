import React, {
  forwardRef,
  useCallback,
  useEffect,
  useRef,
  useState,
  useImperativeHandle,
} from 'react';
import { createRoot, Root } from 'react-dom/client';
import type { ModalProps, ConfirmModalProps, ConfirmType } from './types';
import { cn } from '@utils/cn';
import { Button } from '@components/Button';
import { useFocusTrap, useEscapeKey, generateId, getDialogAriaProps } from '@a11y';
import { createPortal } from 'react-dom';
import styles from './Modal.module.css';

const CloseIcon: React.FC = () => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
    <line x1="18" y1="6" x2="6" y2="18" />
    <line x1="6" y1="6" x2="18" y2="18" />
  </svg>
);

const InfoIcon: React.FC<{ 'data-testid'?: string }> = (props) => (
  <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" {...props}>
    <circle cx="12" cy="12" r="10" />
    <line x1="12" y1="16" x2="12" y2="12" />
    <line x1="12" y1="8" x2="12.01" y2="8" />
  </svg>
);

const SuccessIcon: React.FC<{ 'data-testid'?: string }> = (props) => (
  <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" {...props}>
    <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14" />
    <polyline points="22 4 12 14.01 9 11.01" />
  </svg>
);

const WarningIcon: React.FC<{ 'data-testid'?: string }> = (props) => (
  <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" {...props}>
    <path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z" />
    <line x1="12" y1="9" x2="12" y2="13" />
    <line x1="12" y1="17" x2="12.01" y2="17" />
  </svg>
);

const ErrorIcon: React.FC<{ 'data-testid'?: string }> = (props) => (
  <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" {...props}>
    <circle cx="12" cy="12" r="10" />
    <line x1="15" y1="9" x2="9" y2="15" />
    <line x1="9" y1="9" x2="15" y2="15" />
  </svg>
);

const iconMap: Record<string, React.FC> = {
  info: InfoIcon,
  success: SuccessIcon,
  warning: WarningIcon,
  error: ErrorIcon,
  confirm: WarningIcon,
};

export interface ModalRef {
  focus: () => void;
}

export const Modal = forwardRef<ModalRef, ModalProps>(
  (
    {
      open,
      onClose,
      title,
      children,
      footer,
      width = 520,
      centered = false,
      maskClosable = true,
      closable = true,
      escapable = true,
      destroyOnClose = false,
      className,
      maskClassName,
      okText = '确定',
      cancelText = '取消',
      onOk,
      onCancel,
      okButtonProps,
      cancelButtonProps,
      confirmLoading = false,
      isAlert = false,
    },
    ref,
  ) => {
    const modalRef = useRef<HTMLDivElement>(null);
    const titleId = generateId('modal-title');
    const contentId = generateId('modal-content');
    const [isRendered, setIsRendered] = useState(open);
    const [confirmLoadingState, setConfirmLoadingState] = useState(false);

    const containerRef = useFocusTrap(open);

    useEscapeKey(onClose, open && escapable);

    useEffect(() => {
      if (open) {
        setIsRendered(true);
      }
    }, [open]);

    useImperativeHandle(ref, () => ({
      focus: () => {
        modalRef.current?.focus();
      },
    }));

    useEffect(() => {
      if (open) {
        document.body.style.overflow = 'hidden';
      } else {
        document.body.style.overflow = '';
      }
      return () => {
        document.body.style.overflow = '';
      };
    }, [open]);

    const handleMaskClick = useCallback(() => {
      if (maskClosable) {
        onClose();
        onCancel?.();
      }
    }, [maskClosable, onClose, onCancel]);

    const handleCancel = useCallback(() => {
      onClose();
      onCancel?.();
    }, [onClose, onCancel]);

    const handleOk = useCallback(async () => {
      const result = onOk?.();
      if (result instanceof Promise) {
        setConfirmLoadingState(true);
        try {
          await result;
        } finally {
          setConfirmLoadingState(false);
        }
      }
    }, [onOk]);

    const ariaProps = getDialogAriaProps(isAlert, title ? titleId : undefined, contentId);

    if (!isRendered) return null;

    const modalContent = (
      <div
        className={cn(styles.overlay, centered && styles.overlayCentered, maskClassName)}
        onClick={handleMaskClick}
        data-testid="modal-mask"
      >
        <div
          ref={(node) => {
            (containerRef as React.MutableRefObject<HTMLDivElement | null>).current = node;
            modalRef.current = node;
          }}
          className={cn(styles.modal, className)}
          style={{ width: typeof width === 'number' ? `${width}px` : width }}
          onClick={(e) => e.stopPropagation()}
          tabIndex={-1}
          {...ariaProps}
        >
          {title || closable ? (
            <div className={styles.header}>
              {title ? (
                <h2 id={titleId} className={styles.title}>
                  {title}
                </h2>
              ) : (
                <span />
              )}
              {closable ? (
                <button
                  type="button"
                  className={styles.closeBtn}
                  onClick={handleCancel}
                  aria-label="关闭"
                >
                  <CloseIcon />
                </button>
              ) : null}
            </div>
          ) : null}
          <div id={contentId} className={styles.body}>
            {children}
          </div>
          {footer !== null ? (
            <div className={styles.footer}>
              {footer ?? (
                <>
                  <Button variant="ghost" onClick={handleCancel} {...cancelButtonProps}>
                    {cancelText}
                  </Button>
                  <Button onClick={handleOk} loading={confirmLoading || confirmLoadingState} {...okButtonProps}>
                    {okText}
                  </Button>
                </>
              )}
            </div>
          ) : null}
        </div>
      </div>
    );

    if (!open && destroyOnClose) {
      return null;
    }

    if (typeof document !== 'undefined') {
      return createPortal(modalContent, document.body);
    }

    return null;
  },
);

const createConfirm = (type: ConfirmModalProps['type']) => (
  props: Omit<ConfirmModalProps, 'type'>,
): { destroy: () => void } => {
  return confirm({ ...props, type });
};

const unmountRoot = (root: Root | null, container: HTMLDivElement) => {
  try {
    if (root) {
      root.unmount();
    }
    if (container.parentNode) {
      container.parentNode.removeChild(container);
    }
  } catch (e) {
    // ignore
  }
};

export const confirm: ConfirmType = (props: ConfirmModalProps): { destroy: () => void } => {
  const {
    content,
    type = 'info',
    icon,
    onOk,
    onCancel,
    okText = '确定',
    cancelText = '取消',
    ...rest
  } = props;

  const Icon = iconMap[type] || InfoIcon;
  const iconClassMap: Record<string, string> = {
    info: styles.infoIcon,
    success: styles.successIcon,
    warning: styles.warningIcon,
    error: styles.errorIcon,
    confirm: styles.warningIcon,
  };

  const iconTestIdMap: Record<string, string> = {
    info: 'info-icon',
    success: 'success-icon',
    warning: 'warning-icon',
    error: 'error-icon',
    confirm: 'confirm-icon',
  };

  let destroy: () => void = () => {};

  if (typeof document !== 'undefined') {
    const container = document.createElement('div');
    document.body.appendChild(container);

    let root: Root | null = null;
    let isDestroyed = false;

    const ConfirmContent: React.FC = () => {
      const [open, setOpen] = useState(true);

      useEffect(() => {
        destroy = () => {
          if (isDestroyed) return;
          isDestroyed = true;
          setOpen(false);
          unmountRoot(root, container);
        };
      }, []);

      useEffect(() => {
        if (!open && !isDestroyed) {
          isDestroyed = true;
          unmountRoot(root, container);
        }
      }, [open]);

      const handleOk = () => {
        onOk?.();
        destroy();
      };

      const handleCancel = () => {
        onCancel?.();
        destroy();
      };

      return (
        <Modal
          open={open}
          onClose={handleCancel}
          onOk={handleOk}
          onCancel={handleCancel}
          okText={okText}
          cancelText={cancelText}
          footer={null}
          isAlert={true}
          {...rest}
        >
          <div className={styles.confirmContent}>
            <div className={cn(styles.confirmIcon, iconClassMap[type!])} data-testid={iconTestIdMap[type!]}>
              {icon || <Icon data-testid={`${iconTestIdMap[type!]}-svg`} />}
            </div>
            <div className={styles.confirmText}>
              <p className={styles.confirmContentText}>{content}</p>
              <div style={{ display: 'flex', gap: 'var(--spacing-sm)', justifyContent: 'flex-end', marginTop: 'var(--spacing-lg)' }}>
                <Button variant="ghost" onClick={handleCancel}>
                  {cancelText}
                </Button>
                <Button onClick={handleOk}>
                  {okText}
                </Button>
              </div>
            </div>
          </div>
        </Modal>
      );
    };

    root = createRoot(container);
    root.render(<ConfirmContent />);
  }

  return { destroy };
};

confirm.info = createConfirm('info');
confirm.success = createConfirm('success');
confirm.warning = createConfirm('warning');
confirm.error = createConfirm('error');
confirm.confirm = createConfirm('confirm');

Modal.displayName = 'Modal';

export type { ModalProps, ConfirmModalProps };
