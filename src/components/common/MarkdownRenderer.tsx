'use client';

import * as React from 'react';
import { unified } from 'unified';
import remarkParse from 'remark-parse';
import remarkGfm from 'remark-gfm';
import remarkRehype from 'remark-rehype';
import rehypeRaw from 'rehype-raw';
import rehypeHighlight from 'rehype-highlight';
import rehypeSanitize from 'rehype-sanitize';
import rehypeStringify from 'rehype-stringify';
import { cn } from '@/lib/utils';

interface MarkdownRendererProps extends React.HTMLAttributes<HTMLDivElement> {
  content: string;
  sanitize?: boolean;
}

function MarkdownRenderer({
  content,
  sanitize = true,
  className,
  ...props
}: MarkdownRendererProps) {
  const [html, setHtml] = React.useState<string>('');
  const [isLoading, setIsLoading] = React.useState(true);

  React.useEffect(() => {
    let isMounted = true;
    setIsLoading(true);

    const processor = unified()
      .use(remarkParse)
      .use(remarkGfm)
      .use(remarkRehype, { allowDangerousHtml: true });

    if (sanitize) {
      processor.use(rehypeSanitize);
    }

    processor
      .use(rehypeRaw)
      .use(rehypeHighlight)
      .use(rehypeStringify)
      .process(content)
      .then((file) => {
        if (isMounted) {
          setHtml(String(file));
          setIsLoading(false);
        }
      })
      .catch((error) => {
        console.error('Markdown render error:', error);
        if (isMounted) {
          setHtml('<p>渲染失败</p>');
          setIsLoading(false);
        }
      });

    return () => {
      isMounted = false;
    };
  }, [content, sanitize]);

  if (isLoading) {
    return <div className={cn('animate-pulse', className)}>...</div>;
  }

  return (
    <div
      className={cn(
        'prose prose-sm max-w-none dark:prose-invert',
        'prose-headings:font-semibold',
        'prose-a:text-primary',
        'prose-code:bg-muted prose-code:px-1 prose-code:py-0.5 prose-code:rounded',
        'prose-pre:bg-muted prose-pre:rounded-lg',
        'prose-blockquote:border-l-4 prose-blockquote:border-primary/30 prose-blockquote:pl-4 prose-blockquote:italic',
        'prose-table:w-full prose-table:overflow-auto',
        'prose-th:bg-muted prose-th:px-4 prose-th:py-2',
        'prose-td:border prose-td:px-4 prose-td:py-2',
        'prose-img:rounded-lg prose-img:max-w-full',
        className
      )}
      dangerouslySetInnerHTML={{ __html: html }}
      {...props}
    />
  );
}

export { MarkdownRenderer };
