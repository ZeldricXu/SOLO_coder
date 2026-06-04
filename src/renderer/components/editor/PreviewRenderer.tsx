import React, { useMemo, useCallback, useState, useEffect } from 'react';
import {
  createMarkdownProcessor,
  applyHeadingIds,
  addTargetBlankToLinks,
  extractHeadings,
  getWordCount,
  getReadingTime,
  type OutlineItem,
} from '@core/markdown';

export interface PreviewRendererProps {
  content: string;
  theme?: 'light' | 'dark' | 'system';
  docHash?: string;
  onWikilinkClick?: (target: string) => void;
  onScroll?: (e: React.UIEvent<HTMLDivElement>) => void;
  className?: string;
}

export interface PreviewRendererRef {
  getElement: () => HTMLDivElement | null;
  getScrollTop: () => number;
  getScrollHeight: () => number;
  scrollTo: (scrollTop: number) => void;
}

const PreviewRendererInternal: React.ForwardRefRenderFunction<PreviewRendererRef, PreviewRendererProps> = (
  {
    content,
    docHash,
    onWikilinkClick,
    onScroll,
    className = '',
  },
  ref
) => {
  const previewRef = React.useRef<HTMLDivElement>(null);
  const [renderedHtml, setRenderedHtml] = useState('');

  const markdownProcessor = useMemo(() => createMarkdownProcessor(), []);

  useEffect(() => {
    let cancelled = false;

    const render = async () => {
      if (!content) {
        setRenderedHtml('');
        return;
      }

      try {
        let html = await markdownProcessor.render(content, docHash);
        if (cancelled) return;
        html = applyHeadingIds(html);
        html = addTargetBlankToLinks(html);
        setRenderedHtml(html);
      } catch (error) {
        console.error('Markdown渲染错误:', error);
        if (!cancelled) {
          setRenderedHtml('<p class="text-red-500">渲染失败</p>');
        }
      }
    };

    render();

    return () => {
      cancelled = true;
    };
  }, [content, docHash, markdownProcessor]);

  const outline = useMemo((): OutlineItem[] => {
    const headings = extractHeadings(content);
    return headings.map((h, i) => ({
      ...h,
      line: 0,
    }));
  }, [content]);

  const wordCount = useMemo(() => getWordCount(content), [content]);
  const readingTime = useMemo(() => getReadingTime(content), [content]);
  const lineCount = useMemo(() => content.split('\n').length, [content]);

  const handleLinkClick = useCallback((e: React.MouseEvent<HTMLDivElement>) => {
    const target = e.target as HTMLAnchorElement;
    if (target.tagName === 'A') {
      const href = target.getAttribute('href');
      if (href && href.startsWith('#')) {
        e.preventDefault();
        const id = href.slice(1);
        const element = previewRef.current?.querySelector(`#${id}`);
        if (element) {
          element.scrollIntoView({ behavior: 'smooth', block: 'start' });
        }
      } else if (target.classList.contains('wikilink')) {
        e.preventDefault();
        const linkTarget = target.getAttribute('data-wikilink');
        if (linkTarget && onWikilinkClick) {
          onWikilinkClick(linkTarget);
        }
      }
    }
  }, [onWikilinkClick]);

  const getElement = useCallback(() => previewRef.current, []);
  const getScrollTop = useCallback(() => previewRef.current?.scrollTop || 0, []);
  const getScrollHeight = useCallback(() => previewRef.current?.scrollHeight || 0, []);
  const scrollTo = useCallback((scrollTop: number) => {
    if (previewRef.current) {
      previewRef.current.scrollTop = scrollTop;
    }
  }, []);

  React.useImperativeHandle(
    ref,
    () => ({
      getElement,
      getScrollTop,
      getScrollHeight,
      scrollTo,
    }),
    [getElement, getScrollTop, getScrollHeight, scrollTo]
  );

  return (
    <div
      ref={previewRef}
      onScroll={onScroll}
      onClick={handleLinkClick}
      className={`h-full overflow-auto p-6 ${className}`}
    >
      <article
        className="prose prose-gray max-w-none dark:prose-invert prose-headings:font-bold prose-a:text-blue-500 prose-code:px-1 prose-code:py-0.5 prose-code:bg-gray-100 dark:prose-code:bg-gray-800 prose-code:rounded prose-pre:bg-gray-900 dark:prose-pre:bg-gray-950 prose-blockquote:border-l-4 prose-blockquote:border-blue-500 prose-blockquote:bg-blue-50 dark:prose-blockquote:bg-blue-900/20 prose-img:rounded-lg prose-table:w-full"
        dangerouslySetInnerHTML={{ __html: renderedHtml }}
      />
    </div>
  );
};

export const PreviewRenderer = React.forwardRef<PreviewRendererRef, PreviewRendererProps>(
  PreviewRendererInternal
);

PreviewRenderer.displayName = 'PreviewRenderer';


