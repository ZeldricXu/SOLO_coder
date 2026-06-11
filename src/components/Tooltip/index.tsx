import React, { forwardRef, useCallback, useRef, useState } from 'react';
import type { TooltipProps } from './types';
import { useControllableState } from '@hooks/useControllableState';
import {
  useFloating,
  autoUpdate,
  offset,
  flip,
  shift,
  arrow as floatingArrow,
  useHover,
  useFocus,
  useClick,
  useDismiss,
  useInteractions,
  FloatingPortal,
  FloatingArrow,
  type Placement,
} from '@floating-ui/react';
import { cn } from '@utils/cn';
import { generateId } from '@a11y';
import styles from './Tooltip.module.css';

const placementMap: Record<string, Placement> = {
  top: 'top',
  'top-start': 'top-start',
  'top-end': 'top-end',
  bottom: 'bottom',
  'bottom-start': 'bottom-start',
  'bottom-end': 'bottom-end',
  left: 'left',
  'left-start': 'left-start',
  'left-end': 'left-end',
  right: 'right',
  'right-start': 'right-start',
  'right-end': 'right-end',
};

export const Tooltip = forwardRef<HTMLDivElement, TooltipProps>(
  (
    {
      content,
      children,
      placement = 'top',
      delay = 200,
      disabled = false,
      offset: offsetValue = 8,
      trigger = 'hover',
      visible,
      defaultVisible = false,
      onVisibleChange,
      arrow = true,
    },
    ref,
  ) => {
    const [isOpen, setIsOpen] = useControllableState(visible, defaultVisible, onVisibleChange);
    const arrowRef = useRef<SVGSVGElement>(null);
    const tooltipId = generateId('tooltip');

    const { x, y, strategy, refs, context } = useFloating({
      open: isOpen && !disabled,
      onOpenChange: setIsOpen,
      placement: placementMap[placement],
      whileElementsMounted: autoUpdate,
      middleware: [
        offset(offsetValue),
        flip(),
        shift({ padding: 8 }),
        floatingArrow({ element: arrowRef }),
      ],
    });

    const hover = useHover(context, {
      enabled: trigger === 'hover',
      delay,
      move: false,
    });

    const focus = useFocus(context, {
      enabled: trigger === 'focus',
    });

    const click = useClick(context, {
      enabled: trigger === 'click',
      toggle: true,
    });

    const dismiss = useDismiss(context);

    const { getReferenceProps, getFloatingProps } = useInteractions([
      hover,
      focus,
      click,
      dismiss,
    ]);

    const handleKeyDown = useCallback(
      (e: React.KeyboardEvent) => {
        if (e.key === 'Escape' && isOpen) {
          setIsOpen(false);
        }
      },
      [isOpen, setIsOpen],
    );

    const child = React.Children.only(children);
    const childProps = getReferenceProps({
      ref: refs.setReference,
      onKeyDown: handleKeyDown,
      'aria-describedby': isOpen ? tooltipId : undefined,
    });

    const clonedChild = React.cloneElement(child, childProps);

    return (
      <>
        {clonedChild}
        {isOpen && !disabled ? (
          <FloatingPortal>
            <div
            ref={refs.setFloating}
            id={tooltipId}
            role="tooltip"
            className={styles.content}
            style={{
              position: strategy,
              top: y ?? 0,
              left: x ?? 0,
            }}
            {...getFloatingProps()}
          >
            {content}
            {arrow ? (
              <FloatingArrow
                ref={arrowRef}
                context={context}
                className={cn(
                  styles.arrow,
                  styles[
                    `arrow${placement.charAt(0).toUpperCase()}${placement.slice(1).replace(/-/g, '')}`
                  ] ?? '',
                )}
              />
            ) : null}
          </div>
          </FloatingPortal>
        ) : null}
      </>
    );
  },
);

Tooltip.displayName = 'Tooltip';

export type { TooltipProps, TooltipPlacement };
