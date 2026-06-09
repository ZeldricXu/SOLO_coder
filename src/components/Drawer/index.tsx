import React, { forwardRef, useCallback, useEffect, useState, useRef } from 'react';
import type { DrawerProps } from './types';
import { cn } from '@utils/cn';
import { useFocusTrap, useEscapeKey, generateId, getDialogAriaProps } from '@a11y';
import { createPortal } from 'react-dom';
import styles from './Drawer.module.css';

const CloseIcon: React.FC = () => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
    <line x1="18" y1="6" x2="6" y2="18" />
    <line x1="6" y1="6" x2="18" y2="18" />
  </svg>
);

export const Drawer = forwardRef<HTMLDivElement, DrawerProps>(
  (
    {
      open,
      onClose,
      title,
      children,
      placement = 'right',
      width,
      height,
      closable = true,
      maskClosable = true,
      destroyOnClose = false,
      className,
      maskClassName,
    },
    ref,
  ) => {
    const drawerRef = useRef<HTMLDivElement>(null);
    const titleId = generateId('drawer-title');
    const contentId = generateId('drawer-content');
    const [isRendered, setIsRendered] = useState(open);

    const containerRef = useFocusTrap(open);

    useEscapeKey(onClose, open);

    useEffect(() => {
      if (open) {
        setIsRendered(true);
      }
    }, [open]);

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
      }
    }, [maskClosable, onClose]);

    const ariaProps = getDialogAriaProps(false, title ? titleId : undefined, contentId);

    if (!isRendered) return null;

    const drawerStyle: React.CSSProperties = {};
    if ((placement === 'left' || placement === 'right') && width !== undefined) {
      drawerStyle.width = typeof width === 'number' ? `${width}px` : width;
    }
    if ((placement === 'top' || placement === 'bottom') && height !== undefined) {
      drawerStyle.height = typeof height === 'number' ? `${height}px` : height;
    }

    const drawerContent = (
      <>
        <div
          className={cn(styles.overlay, maskClassName)}
          onClick={handleMaskClick}
          aria-hidden="true"
        />
        <div
          ref={(node) => {
            (containerRef as React.MutableRefObject<HTMLDivElement | null>).current = node;
            if (typeof ref === 'function') {
              ref(node);
            } else if (ref !== null) {
              ref.current = node;
            }
            drawerRef.current = node;
          }}
          className={cn(styles.drawer, styles[placement], className)}
          style={drawerStyle}
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
                  onClick={onClose}
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
        </div>
      </>
    );

    if (!open && destroyOnClose) {
      return null;
    }

    if (typeof document !== 'undefined') {
      return createPortal(drawerContent, document.body);
    }

    return null;
  },
);

Drawer.displayName = 'Drawer';

export type { DrawerProps, DrawerPlacement };
