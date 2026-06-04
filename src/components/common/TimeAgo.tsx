'use client';

import * as React from 'react';
import { formatTimeAgo } from '@/lib/utils';

interface TimeAgoProps extends React.HTMLAttributes<HTMLSpanElement> {
  date: Date | null | undefined;
  showTooltip?: boolean;
  format?: 'relative' | 'absolute';
}

function TimeAgo({
  date,
  showTooltip = true,
  format = 'relative',
  className,
  ...props
}: TimeAgoProps) {
  const [, forceUpdate] = React.useState(0);

  React.useEffect(() => {
    const timer = setInterval(() => {
      forceUpdate((n) => n + 1);
    }, 60000);

    return () => {
      clearInterval(timer);
    };
  }, []);

  if (!date) {
    return null;
  }

  const dateObj = new Date(date);
  const relativeTime = formatTimeAgo(dateObj);
  const absoluteTime = dateObj.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });

  const displayText = format === 'relative' ? relativeTime : absoluteTime;

  if (showTooltip) {
    return (
      <span
        className={className}
        title={absoluteTime}
        {...props}
      >
        {displayText}
      </span>
    );
  }

  return (
    <span className={className} {...props}>
      {displayText}
    </span>
  );
}

export { TimeAgo };
