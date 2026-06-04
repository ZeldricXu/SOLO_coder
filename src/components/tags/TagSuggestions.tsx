'use client';

import { useState } from 'react';
import { Sparkles, X, Check, RefreshCw, ChevronDown, ChevronUp } from 'lucide-react';
import { cn } from '@/lib/utils';
import type { TagSuggestion } from '@/lib/nlp/types';

interface TagSuggestionsProps {
  suggestions: (TagSuggestion & {
    isExisting?: boolean;
    tagId?: string;
  })[];
  selectedTagNames: string[];
  onApplyTag: (suggestion: TagSuggestion & {
    isExisting?: boolean;
    tagId?: string;
  }) => void;
  onApplyAll: () => void;
  onDismiss: (suggestion: TagSuggestion) => void;
  onDismissAll: () => void;
  onRefresh?: () => void;
  isLoading?: boolean;
  className?: string;
}

const SOURCE_LABELS: Record<string, { label: string; color: string }> = {
  keyword: { label: '关键词', color: '#3b82f6' },
  classification: { label: '分类', color: '#8b5cf6' },
  trending: { label: '热门', color: '#10b981' },
};

export function TagSuggestions({
  suggestions,
  selectedTagNames,
  onApplyTag,
  onApplyAll,
  onDismiss,
  onDismissAll,
  onRefresh,
  isLoading = false,
  className,
}: TagSuggestionsProps) {
  const [isExpanded, setIsExpanded] = useState(true);
  const [dismissedTags, setDismissedTags] = useState<Set<string>>(new Set());

  const visibleSuggestions = suggestions.filter(
    (s) => !dismissedTags.has(s.name) && !selectedTagNames.includes(s.name)
  );

  const handleDismiss = (suggestion: TagSuggestion) => {
    setDismissedTags((prev) => new Set(prev).add(suggestion.name));
    onDismiss(suggestion);
  };

  const getConfidenceColor = (confidence: number) => {
    if (confidence >= 0.8) return '#22c55e';
    if (confidence >= 0.6) return '#3b82f6';
    if (confidence >= 0.4) return '#f59e0b';
    return '#6b7280';
  };

  const getContrastColor = (hexColor: string) => {
    const r = parseInt(hexColor.slice(1, 3), 16);
    const g = parseInt(hexColor.slice(3, 5), 16);
    const b = parseInt(hexColor.slice(5, 7), 16);
    const luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
    return luminance > 0.5 ? '#000000' : '#ffffff';
  };

  if (visibleSuggestions.length === 0 && !isLoading) {
    return null;
  }

  return (
    <div className={cn('bg-card border rounded-lg overflow-hidden', className)}>
      <div className="flex items-center justify-between px-4 py-3 bg-muted/50 border-b">
        <button
          type="button"
          onClick={() => setIsExpanded(!isExpanded)}
          className="flex items-center gap-2 text-sm font-medium hover:text-foreground/80 transition-colors"
        >
          <Sparkles className="h-4 w-4 text-primary" />
          <span>AI 推荐标签</span>
          <span className="bg-primary/10 text-primary text-xs px-2 py-0.5 rounded-full">
            {visibleSuggestions.length}
          </span>
          {isExpanded ? (
            <ChevronUp className="h-4 w-4 text-muted-foreground" />
          ) : (
            <ChevronDown className="h-4 w-4 text-muted-foreground" />
          )}
        </button>

        <div className="flex items-center gap-2">
          {onRefresh && (
            <button
              type="button"
              onClick={onRefresh}
              disabled={isLoading}
              className="p-1.5 rounded-md hover:bg-muted transition-colors"
              title="刷新建议"
            >
              <RefreshCw className={cn('h-4 w-4', isLoading && 'animate-spin')} />
            </button>
          )}
          {visibleSuggestions.length > 0 && (
            <>
              <button
                type="button"
                onClick={onApplyAll}
                className="text-xs text-primary hover:text-primary/80 font-medium transition-colors"
              >
                全部应用
              </button>
              <button
                type="button"
                onClick={onDismissAll}
                className="text-xs text-muted-foreground hover:text-foreground transition-colors"
              >
                全部忽略
              </button>
            </>
          )}
        </div>
      </div>

      {isExpanded && (
        <div className="p-3">
          {isLoading ? (
            <div className="space-y-2">
              {[1, 2, 3].map((i) => (
                <div
                  key={i}
                  className="h-10 bg-muted animate-pulse rounded-md"
                />
              ))}
            </div>
          ) : (
            <div className="space-y-2">
              {visibleSuggestions.map((suggestion) => {
                const sourceInfo = SOURCE_LABELS[suggestion.source];
                return (
                  <div
                    key={suggestion.name}
                    className="flex items-center gap-3 p-2 rounded-md hover:bg-muted/50 transition-colors group"
                  >
                    <button
                      type="button"
                      onClick={() => onApplyTag(suggestion)}
                      className="flex-1 flex items-center gap-2 text-left"
                    >
                      <span
                        className="inline-flex items-center gap-1 px-2 py-1 rounded-full text-xs font-medium"
                        style={{
                          backgroundColor: suggestion.color || '#6b7280',
                          color: suggestion.color
                            ? getContrastColor(suggestion.color)
                            : '#ffffff',
                        }}
                      >
                        {suggestion.name}
                        {suggestion.isExisting && (
                          <Check className="h-3 w-3 opacity-70" />
                        )}
                      </span>

                      <span
                        className="text-xs px-1.5 py-0.5 rounded"
                        style={{
                          backgroundColor: `${sourceInfo.color}15`,
                          color: sourceInfo.color,
                        }}
                      >
                        {sourceInfo.label}
                      </span>

                      <div className="flex-1" />

                      <div className="flex items-center gap-1">
                        <div className="w-16 h-1.5 bg-muted rounded-full overflow-hidden">
                          <div
                            className="h-full rounded-full transition-all"
                            style={{
                              width: `${suggestion.confidence * 100}%`,
                              backgroundColor: getConfidenceColor(suggestion.confidence),
                            }}
                          />
                        </div>
                        <span className="text-xs text-muted-foreground w-8 text-right">
                          {Math.round(suggestion.confidence * 100)}%
                        </span>
                      </div>
                    </button>

                    <button
                      type="button"
                      onClick={() => handleDismiss(suggestion)}
                      className="p-1 rounded-md hover:bg-muted opacity-0 group-hover:opacity-100 transition-all"
                      title="忽略此建议"
                    >
                      <X className="h-4 w-4 text-muted-foreground" />
                    </button>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
