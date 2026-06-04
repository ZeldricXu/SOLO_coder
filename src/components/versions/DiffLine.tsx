'use client';

import React, { useState } from 'react';
import { Copy, Check } from 'lucide-react';
import type { Change } from 'diff';
import type { DiffLine as DiffLineType, DiffViewMode } from '@/lib/types/version';
import { cn } from '@/lib/utils';

interface DiffLineProps {
  line: DiffLineType;
  viewMode?: DiffViewMode;
  showLineNumbers?: boolean;
  showActions?: boolean;
  onCopy?: (content: string) => void;
  className?: string;
}

export function DiffLine({
  line,
  viewMode = 'unified',
  showLineNumbers = true,
  showActions = true,
  onCopy,
  className,
}: DiffLineProps) {
  const [copied, setCopied] = useState(false);
  const [isHovered, setIsHovered] = useState(false);

  const handleCopy = () => {
    void navigator.clipboard.writeText(line.content);
    setCopied(true);
    onCopy?.(line.content);
    setTimeout(() => setCopied(false), 2000);
  };

  const renderCharChanges = (charChanges: Change[] | undefined) => {
    if (!charChanges || charChanges.length === 0) {
      return line.content;
    }

    return charChanges.map((change, index) => {
      if (change.added) {
        return (
          <span
            key={index}
            className="bg-green-200 text-green-900 px-0.5 rounded"
          >
            {change.value}
          </span>
        );
      }
      if (change.removed) {
        return (
          <span
            key={index}
            className="bg-red-200 text-red-900 px-0.5 rounded line-through"
          >
            {change.value}
          </span>
        );
      }
      return <span key={index}>{change.value}</span>;
    });
  };

  const getLineBackgroundClass = () => {
    switch (line.type) {
      case 'added':
        return 'bg-green-50';
      case 'removed':
        return 'bg-red-50';
      case 'modified':
        return 'bg-yellow-50';
      default:
        return 'bg-white';
    }
  };

  const getLinePrefix = () => {
    if (viewMode !== 'unified') return null;
    switch (line.type) {
      case 'added':
        return <span className="text-green-600 font-bold mr-2">+</span>;
      case 'removed':
        return <span className="text-red-600 font-bold mr-2">-</span>;
      default:
        return <span className="text-gray-400 mr-2"> </span>;
    }
  };

  const renderLineNumber = (num: number | null, side: 'old' | 'new') => {
    if (!showLineNumbers) return null;

    const isOld = side === 'old';
    const shouldShow = isOld
      ? line.type === 'removed' || line.type === 'unchanged' || line.type === 'modified'
      : line.type === 'added' || line.type === 'unchanged' || line.type === 'modified';

    if (!shouldShow && viewMode === 'unified') {
      return (
        <td className="select-none px-2 py-0.5 text-right text-gray-300 text-sm bg-gray-50 border-r border-gray-200 w-12" />
      );
    }

    return (
      <td
        className={cn(
          'select-none px-2 py-0.5 text-right text-sm border-r border-gray-200 w-12',
          num === null ? 'text-gray-300 bg-gray-50' : 'text-gray-500 bg-gray-50'
        )}
      >
        {num ?? ''}
      </td>
    );
  };

  if (viewMode === 'split') {
    const isAdded = line.type === 'added';
    const isRemoved = line.type === 'removed';
    const isModified = line.type === 'modified';

    if (isAdded) {
      return (
        <tr
          className={cn('group', getLineBackgroundClass(), className)}
          onMouseEnter={() => setIsHovered(true)}
          onMouseLeave={() => setIsHovered(false)}
        >
          <td className="select-none px-2 py-0.5 text-right text-gray-300 text-sm bg-gray-50 border-r border-gray-200 w-12" />
          <td
            className={cn(
              'select-none px-2 py-0.5 text-right text-sm border-r border-gray-200 w-12',
              'text-gray-500 bg-green-50'
            )}
          >
            {line.lineNumberNew}
          </td>
          <td
            className={cn(
              'px-3 py-0.5 text-sm whitespace-pre font-mono relative',
              'bg-green-50'
            )}
          >
            <span className="text-green-600 font-bold mr-2">+</span>
            {renderCharChanges(line.charChanges)}
            {showActions && isHovered && (
              <button
                onClick={handleCopy}
                className="absolute right-2 top-1/2 -translate-y-1/2 p-1 rounded bg-white/80 hover:bg-white shadow-sm opacity-0 group-hover:opacity-100 transition-opacity"
                title="复制行"
              >
                {copied ? (
                  <Check className="w-3 h-3 text-green-600" />
                ) : (
                  <Copy className="w-3 h-3 text-gray-500" />
                )}
              </button>
            )}
          </td>
        </tr>
      );
    }

    if (isRemoved) {
      return (
        <tr
          className={cn('group', getLineBackgroundClass(), className)}
          onMouseEnter={() => setIsHovered(true)}
          onMouseLeave={() => setIsHovered(false)}
        >
          <td
            className={cn(
              'select-none px-2 py-0.5 text-right text-sm border-r border-gray-200 w-12',
              'text-gray-500 bg-red-50'
            )}
          >
            {line.lineNumberOld}
          </td>
          <td className="select-none px-2 py-0.5 text-right text-gray-300 text-sm bg-gray-50 border-r border-gray-200 w-12" />
          <td
            className={cn(
              'px-3 py-0.5 text-sm whitespace-pre font-mono relative',
              'bg-red-50'
            )}
          >
            <span className="text-red-600 font-bold mr-2">-</span>
            {renderCharChanges(line.charChanges)}
            {showActions && isHovered && (
              <button
                onClick={handleCopy}
                className="absolute right-2 top-1/2 -translate-y-1/2 p-1 rounded bg-white/80 hover:bg-white shadow-sm opacity-0 group-hover:opacity-100 transition-opacity"
                title="复制行"
              >
                {copied ? (
                  <Check className="w-3 h-3 text-green-600" />
                ) : (
                  <Copy className="w-3 h-3 text-gray-500" />
                )}
              </button>
            )}
          </td>
        </tr>
      );
    }

    return (
      <tr
        className={cn('group', getLineBackgroundClass(), className)}
        onMouseEnter={() => setIsHovered(true)}
        onMouseLeave={() => setIsHovered(false)}
      >
        <td
          className={cn(
            'select-none px-2 py-0.5 text-right text-sm border-r border-gray-200 w-12',
            line.lineNumberOld === null ? 'text-gray-300 bg-gray-50' : 'text-gray-500 bg-gray-50'
          )}
        >
          {line.lineNumberOld ?? ''}
        </td>
        <td
          className={cn(
            'select-none px-2 py-0.5 text-right text-sm border-r border-gray-200 w-12',
            line.lineNumberNew === null ? 'text-gray-300 bg-gray-50' : 'text-gray-500 bg-gray-50'
          )}
        >
          {line.lineNumberNew ?? ''}
        </td>
        <td
          className={cn(
            'px-3 py-0.5 text-sm whitespace-pre font-mono relative',
            isModified && 'bg-yellow-50'
          )}
        >
          <span className="text-gray-400 mr-2"> </span>
          {isModified ? renderCharChanges(line.charChanges) : line.content}
          {showActions && isHovered && (
            <button
              onClick={handleCopy}
              className="absolute right-2 top-1/2 -translate-y-1/2 p-1 rounded bg-white/80 hover:bg-white shadow-sm opacity-0 group-hover:opacity-100 transition-opacity"
              title="复制行"
            >
              {copied ? (
                <Check className="w-3 h-3 text-green-600" />
              ) : (
                <Copy className="w-3 h-3 text-gray-500" />
              )}
            </button>
          )}
        </td>
      </tr>
    );
  }

  return (
    <tr
      className={cn('group', getLineBackgroundClass(), className)}
      onMouseEnter={() => setIsHovered(true)}
      onMouseLeave={() => setIsHovered(false)}
    >
      {renderLineNumber(line.lineNumberOld, 'old')}
      {renderLineNumber(line.lineNumberNew, 'new')}
      <td className="px-3 py-0.5 text-sm whitespace-pre font-mono relative">
        {getLinePrefix()}
        {line.type === 'modified'
          ? renderCharChanges(line.charChanges)
          : line.content}
        {showActions && isHovered && (
          <button
            onClick={handleCopy}
            className="absolute right-2 top-1/2 -translate-y-1/2 p-1 rounded bg-white/80 hover:bg-white shadow-sm opacity-0 group-hover:opacity-100 transition-opacity"
            title="复制行"
          >
            {copied ? (
              <Check className="w-3 h-3 text-green-600" />
            ) : (
              <Copy className="w-3 h-3 text-gray-500" />
            )}
          </button>
        )}
      </td>
    </tr>
  );
}
