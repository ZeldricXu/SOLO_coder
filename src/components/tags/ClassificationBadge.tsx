'use client';

import { useState } from 'react';
import * as DropdownMenu from '@radix-ui/react-dropdown-menu';
import { ChevronDown, Check, Sparkles } from 'lucide-react';
import { cn } from '@/lib/utils';
import {
  DocumentType,
  DocumentTypeLabels,
  DocumentTypeColors,
} from '@/lib/nlp/types';

interface ClassificationBadgeProps {
  type: DocumentType;
  confidence?: number;
  onTypeChange?: (type: DocumentType) => void;
  showConfidence?: boolean;
  showDropdown?: boolean;
  className?: string;
}

export function ClassificationBadge({
  type,
  confidence,
  onTypeChange,
  showConfidence = true,
  showDropdown = true,
  className,
}: ClassificationBadgeProps) {
  const [isOpen, setIsOpen] = useState(false);
  const color = DocumentTypeColors[type];
  const label = DocumentTypeLabels[type];

  const getContrastColor = (hexColor: string) => {
    const r = parseInt(hexColor.slice(1, 3), 16);
    const g = parseInt(hexColor.slice(3, 5), 16);
    const b = parseInt(hexColor.slice(5, 7), 16);
    const luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
    return luminance > 0.5 ? '#000000' : '#ffffff';
  };

  const getConfidenceColor = (confidence: number) => {
    if (confidence >= 0.8) return 'text-green-500';
    if (confidence >= 0.6) return 'text-blue-500';
    if (confidence >= 0.4) return 'text-yellow-500';
    return 'text-gray-500';
  };

  const allTypes = Object.values(DocumentType).filter(
    (t) => t !== DocumentType.OTHER
  );

  if (!showDropdown) {
    return (
      <span
        className={cn(
          'inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-medium',
          className
        )}
        style={{
          backgroundColor: `${color}20`,
          color: color,
        }}
      >
        <span
          className="h-2 w-2 rounded-full"
          style={{ backgroundColor: color }}
        />
        <span>{label}</span>
        {showConfidence && confidence !== undefined && (
          <span className={cn('text-xs', getConfidenceColor(confidence))}>
            {Math.round(confidence * 100)}%
          </span>
        )}
      </span>
    );
  }

  return (
    <DropdownMenu.Root open={isOpen} onOpenChange={setIsOpen}>
      <DropdownMenu.Trigger asChild>
        <button
          type="button"
          className={cn(
            'inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-medium transition-all hover:opacity-90',
            className
          )}
          style={{
            backgroundColor: `${color}20`,
            color: color,
          }}
        >
          <span
            className="h-2 w-2 rounded-full"
            style={{ backgroundColor: color }}
          />
          <span>{label}</span>
          {showConfidence && confidence !== undefined && (
            <span className={cn('text-xs', getConfidenceColor(confidence))}>
              {Math.round(confidence * 100)}%
            </span>
          )}
          <ChevronDown className={cn('h-3 w-3 transition-transform', isOpen && 'rotate-180')} />
        </button>
      </DropdownMenu.Trigger>

      <DropdownMenu.Portal>
        <DropdownMenu.Content
          className="min-w-[200px] bg-background border rounded-lg shadow-lg p-1 z-50"
          align="start"
          sideOffset={4}
        >
          <div className="px-2 py-1.5 text-xs text-muted-foreground border-b mb-1">
            文档类型
          </div>
          {allTypes.map((docType) => (
            <DropdownMenu.Item
              key={docType}
              onClick={() => onTypeChange?.(docType)}
              className={cn(
                'flex items-center gap-2 px-2 py-1.5 rounded-md text-sm cursor-pointer transition-colors',
                'hover:bg-muted',
                type === docType && 'bg-muted/50'
              )}
            >
              <span
                className="h-3 w-3 rounded-full flex-shrink-0"
                style={{ backgroundColor: DocumentTypeColors[docType] }}
              />
              <span className="flex-1">{DocumentTypeLabels[docType]}</span>
              {type === docType && (
                <Check className="h-4 w-4 text-primary" />
              )}
            </DropdownMenu.Item>
          ))}
          <DropdownMenu.Separator className="h-px bg-border my-1" />
          <DropdownMenu.Item
            onClick={() => onTypeChange?.(DocumentType.OTHER)}
            className={cn(
              'flex items-center gap-2 px-2 py-1.5 rounded-md text-sm cursor-pointer transition-colors',
              'hover:bg-muted',
              type === DocumentType.OTHER && 'bg-muted/50'
            )}
          >
            <span
              className="h-3 w-3 rounded-full flex-shrink-0"
              style={{ backgroundColor: DocumentTypeColors[DocumentType.OTHER] }}
            />
            <span className="flex-1">{DocumentTypeLabels[DocumentType.OTHER]}</span>
            {type === DocumentType.OTHER && (
              <Check className="h-4 w-4 text-primary" />
            )}
          </DropdownMenu.Item>
        </DropdownMenu.Content>
      </DropdownMenu.Portal>
    </DropdownMenu.Root>
  );
}

interface ClassificationIndicatorProps {
  isAutoClassified?: boolean;
  onReclassify?: () => void;
  isLoading?: boolean;
  className?: string;
}

export function ClassificationIndicator({
  isAutoClassified,
  onReclassify,
  isLoading = false,
  className,
}: ClassificationIndicatorProps) {
  if (!isAutoClassified) return null;

  return (
    <div
      className={cn(
        'inline-flex items-center gap-1.5 px-2 py-1 rounded-md bg-primary/10 text-primary text-xs',
        className
      )}
    >
      <Sparkles className={cn('h-3 w-3', isLoading && 'animate-spin')} />
      <span>AI 自动分类</span>
      {onReclassify && (
        <button
          type="button"
          onClick={onReclassify}
          disabled={isLoading}
          className="ml-1 hover:underline disabled:opacity-50"
        >
          重新分类
        </button>
      )}
    </div>
  );
}
