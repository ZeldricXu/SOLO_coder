import React, {
  useState,
  useRef,
  useCallback,
  useMemo,
  useEffect,
  useImperativeHandle,
  forwardRef,
  useLayoutEffect,
} from 'react';
import {
  VirtualScrollController,
  createVirtualScrollController,
} from './VirtualScrollController';

const DEFAULT_ITEM_HEIGHT = 80;
const DEFAULT_OVERSCAN = 5;
const VIRTUALIZATION_THRESHOLD = 20;

const VirtualList = forwardRef(
  (
    {
      items = [],
      renderItem,
      itemKey,
      itemHeight = DEFAULT_ITEM_HEIGHT,
      overscan = DEFAULT_OVERSCAN,
      threshold = VIRTUALIZATION_THRESHOLD,
      onScroll,
      onVisibleRangeChange,
      style,
      className,
    },
    ref
  ) => {
    const containerRef = useRef(null);
    const itemCount = items.length;

    const controllerRef = useRef(
      createVirtualScrollController({
        itemHeight,
        overscan,
        threshold,
      })
    );

    const controller = controllerRef.current;

    const [renderState, setRenderState] = useState(() => ({
      visibleRange: controller.getVisibleRange(),
      totalHeight: controller.totalHeight,
      shouldVirtualize: controller.shouldVirtualize,
    }));

    useLayoutEffect(() => {
      controller.updateItemCount(itemCount);
    }, [itemCount, controller]);

    useLayoutEffect(() => {
      if (controller.itemHeight !== itemHeight) {
        controller.itemHeight = itemHeight;
        controller.resetMeasurements();
      }
    }, [itemHeight, controller]);

    useEffect(() => {
      const handleVisibleRangeChange = (range) => {
        setRenderState((prev) => ({
          ...prev,
          visibleRange: range,
        }));
        if (onVisibleRangeChange) {
          onVisibleRangeChange(range);
        }
      };

      const handleTotalHeightChange = (height) => {
        setRenderState((prev) => ({
          ...prev,
          totalHeight: height,
        }));
      };

      const handleStateChange = (state) => {
        setRenderState((prev) => ({
          ...prev,
          shouldVirtualize: controller.shouldVirtualize,
        }));
      };

      const unsubscribe1 = controller.subscribe(
        'visibleRangeChange',
        handleVisibleRangeChange
      );
      const unsubscribe2 = controller.subscribe(
        'totalHeightChange',
        handleTotalHeightChange
      );
      const unsubscribe3 = controller.subscribe(
        'stateChange',
        handleStateChange
      );

      return () => {
        unsubscribe1();
        unsubscribe2();
        unsubscribe3();
      };
    }, [controller, onVisibleRangeChange]);

    useEffect(() => {
      if (!containerRef.current) return;

      const updateContainerHeight = () => {
        if (containerRef.current) {
          controller.updateContainerHeight(containerRef.current.clientHeight);
        }
      };

      updateContainerHeight();

      const resizeObserver = new ResizeObserver(updateContainerHeight);
      resizeObserver.observe(containerRef.current);

      return () => {
        resizeObserver.disconnect();
      };
    }, [controller]);

    useImperativeHandle(
      ref,
      () => ({
        scrollTo: (index) => {
          if (containerRef.current) {
            containerRef.current.scrollTop = controller.scrollTo(index);
          }
        },
        scrollToTop: () => {
          if (containerRef.current) {
            containerRef.current.scrollTop = controller.scrollToTop();
          }
        },
        scrollToBottom: () => {
          if (containerRef.current) {
            containerRef.current.scrollTop = controller.scrollToBottom();
          }
        },
        getController: () => controller,
        getState: () => controller.getState(),
      }),
      [controller]
    );

    const handleScroll = useCallback(
      (e) => {
        controller.updateScrollTop(e.currentTarget.scrollTop);
        if (onScroll) {
          onScroll(e);
        }
      },
      [controller, onScroll]
    );

    const measureItem = useCallback(
      (index, height) => {
        controller.measureItem(index, height);
      },
      [controller]
    );

    const ItemWrapper = useMemo(
      () =>
        function ItemWrapperComponent({ item, index, style: itemStyle }) {
          const itemRef = useRef(null);

          useEffect(() => {
            if (!itemRef.current || !renderState.shouldVirtualize) return;

            const height = itemRef.current.getBoundingClientRect().height;
            if (height > 0) {
              measureItem(index, height);
            }
          }, [index, measureItem, renderState.shouldVirtualize]);

          const key = itemKey
            ? typeof itemKey === 'function'
              ? itemKey(item, index)
              : item[itemKey]
            : index;

          return (
            <div ref={itemRef} style={itemStyle} key={key}>
              {renderItem(item, index)}
            </div>
          );
        },
      [renderItem, itemKey, renderState.shouldVirtualize, measureItem]
    );

    const { visibleRange, shouldVirtualize, totalHeight } = renderState;

    const visibleItems = useMemo(() => {
      if (!shouldVirtualize) {
        return items.map((item, index) => (
          <ItemWrapper key={index} item={item} index={index} />
        ));
      }

      const result = [];

      for (
        let i = visibleRange.startIndex;
        i <= visibleRange.endIndex && i < items.length;
        i++
      ) {
        const item = items[i];
        const itemStyle = controller.getItemStyle(i);

        result.push(
          <ItemWrapper
            key={i}
            item={item}
            index={i}
            style={itemStyle}
          />
        );
      }

      return result;
    }, [
      items,
      shouldVirtualize,
      visibleRange,
      ItemWrapper,
      controller,
    ]);

    if (items.length === 0) {
      return null;
    }

    if (!shouldVirtualize) {
      return (
        <div
          ref={containerRef}
          style={style}
          className={className}
          onScroll={handleScroll}
        >
          {visibleItems}
        </div>
      );
    }

    const containerStyle = {
      ...style,
      ...controller.getContainerStyle(),
    };

    const spacerStyle = controller.getSpacerStyle();

    return (
      <div
        ref={containerRef}
        style={containerStyle}
        className={className}
        onScroll={handleScroll}
      >
        <div style={spacerStyle}>{visibleItems}</div>
      </div>
    );
  }
);

VirtualList.displayName = 'VirtualList';

export const useVirtualList = ({
  items,
  itemHeight = DEFAULT_ITEM_HEIGHT,
  overscan = DEFAULT_OVERSCAN,
  threshold = VIRTUALIZATION_THRESHOLD,
}) => {
  const controllerRef = useRef(
    createVirtualScrollController({
      itemHeight,
      overscan,
      threshold,
    })
  );

  const controller = controllerRef.current;
  const itemCount = items.length;

  const [renderState, setRenderState] = useState(() => ({
    visibleRange: controller.getVisibleRange(),
    shouldVirtualize: controller.shouldVirtualize,
  }));

  useLayoutEffect(() => {
    controller.updateItemCount(itemCount);
  }, [itemCount, controller]);

  useEffect(() => {
    const handleVisibleRangeChange = (range) => {
      setRenderState((prev) => ({
        ...prev,
        visibleRange: range,
      }));
    };

    const handleStateChange = (state) => {
      setRenderState((prev) => ({
        ...prev,
        shouldVirtualize: controller.shouldVirtualize,
      }));
    };

    const unsubscribe1 = controller.subscribe(
      'visibleRangeChange',
      handleVisibleRangeChange
    );
    const unsubscribe2 = controller.subscribe(
      'stateChange',
      handleStateChange
    );

    return () => {
      unsubscribe1();
      unsubscribe2();
    };
  }, [controller]);

  const { visibleRange, shouldVirtualize } = renderState;

  const visibleItems = useMemo(() => {
    if (!shouldVirtualize) {
      return items;
    }
    return items.slice(visibleRange.startIndex, visibleRange.endIndex + 1);
  }, [items, visibleRange, shouldVirtualize]);

  const totalHeight = shouldVirtualize ? controller.totalHeight : 'auto';

  const getItemOffset = useCallback(
    (index) => {
      return controller.getItemOffset(index);
    },
    [controller]
  );

  const handleScroll = useCallback(
    (e) => {
      controller.updateScrollTop(e.currentTarget.scrollTop);
    },
    [controller]
  );

  const setRef = useCallback(
    (node) => {
      if (node) {
        controller.updateContainerHeight(node.clientHeight);
      }
    },
    [controller]
  );

  return {
    containerRef: setRef,
    visibleItems,
    startIndex: visibleRange.startIndex,
    endIndex: visibleRange.endIndex,
    totalHeight,
    getItemOffset,
    handleScroll,
    shouldVirtualize,
    controller,
  };
};

export { VirtualScrollController, createVirtualScrollController };

export default VirtualList;
