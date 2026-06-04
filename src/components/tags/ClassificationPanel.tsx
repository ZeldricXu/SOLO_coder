'use client';

import { useState } from 'react';
import { Sparkles, RefreshCw, Info, ChevronDown, ChevronUp, Check } from 'lucide-react';
import { cn } from '@/lib/utils';
import { ClassificationBadge } from './ClassificationBadge';
import {
  DocumentType,
  DocumentTypeLabels,
  DocumentTypeColors,
  ClassificationResult,
} from '@/lib/nlp/types';

interface ClassificationPanelProps {
  classification: ClassificationResult | null;
  isLoading?: boolean;
  onReclassify?: () => void;
  onTypeChange?: (type: DocumentType) => void;
  onApplyClassification?: () => void;
  showApplyButton?: boolean;
  className?: string;
}

export function ClassificationPanel({
  classification,
  isLoading = false,
  onReclassify,
  onTypeChange,
  onApplyClassification,
  showApplyButton = false,
  className,
}: ClassificationPanelProps) {
  const [isExpanded, setIsExpanded] = useState(true);
  const [showAllScores, setShowAllScores] = useState(false);

  const getConfidenceColor = (confidence: number) => {
    if (confidence >= 0.8) return 'text-green-600 bg-green-50';
    if (confidence >= 0.6) return 'text-blue-600 bg-blue-50';
    if (confidence >= 0.4) return 'text-yellow-600 bg-yellow-50';
    return 'text-gray-600 bg-gray-50';
  };

  const getConfidenceBarColor = (confidence: number) => {
    if (confidence >= 0.8) return 'bg-green-500';
    if (confidence >= 0.6) return 'bg-blue-500';
    if (confidence >= 0.4) return 'bg-yellow-500';
    return 'bg-gray-400';
  };

  const allTypes = Object.values(DocumentType).filter(
    (t) => t !== DocumentType.OTHER
  );

  return (
    <div className={cn('bg-card border rounded-lg overflow-hidden', className)}>
      <div className="flex items-center justify-between px-4 py-3 bg-muted/50 border-b">
        <button
          type="button"
          onClick={() => setIsExpanded(!isExpanded)}
          className="flex items-center gap-2 text-sm font-medium hover:text-foreground/80 transition-colors"
        >
          <Sparkles className="h-4 w-4 text-primary" />
          <span>文档分类</span>
          {classification && (
            <ClassificationBadge
              type={classification.type}
              confidence={classification.confidence}
              showDropdown={false}
            />
          )}
          {isExpanded ? (
            <ChevronUp className="h-4 w-4 text-muted-foreground" />
          ) : (
            <ChevronDown className="h-4 w-4 text-muted-foreground" />
          )}
        </button>

        <div className="flex items-center gap-2">
          {onReclassify && (
            <button
              type="button"
              onClick={onReclassify}
              disabled={isLoading}
              className="p-1.5 rounded-md hover:bg-muted transition-colors"
              title="重新分类"
            >
              <RefreshCw className={cn('h-4 w-4', isLoading && 'animate-spin')} />
            </button>
          )}
          {showApplyButton && classification && (
            <button
              type="button"
              onClick={onApplyClassification}
              className="h-8 px-3 text-xs bg-primary text-primary-foreground rounded-md hover:bg-primary/90 transition-colors flex items-center gap-1"
            >
              <Check className="h-3 w-3" />
              应用分类
            </button>
          )}
        </div>
      </div>

      {isExpanded && (
        <div className="p-4 space-y-4">
          {isLoading ? (
            <div className="space-y-3">
              <div className="h-6 bg-muted animate-pulse rounded w-1/2" />
              <div className="h-20 bg-muted animate-pulse rounded" />
              <div className="h-10 bg-muted animate-pulse rounded" />
            </div>
          ) : classification ? (
            <>
              <div className="space-y-3">
                <div className="flex items-center justify-between">
                  <span className="text-sm font-medium">自动分类结果</span>
                  <ClassificationBadge
                    type={classification.type}
                    confidence={classification.confidence}
                    onTypeChange={onTypeChange}
                  />
                </div>

                <div className="space-y-2">
                  <div className="flex items-center justify-between text-sm">
                    <span className="text-muted-foreground">置信度</span>
                    <span
                      className={cn(
                        'font-medium px-2 py-0.5 rounded text-xs',
                        getConfidenceColor(classification.confidence)
                      )}
                    >
                      {Math.round(classification.confidence * 100)}%
                    </span>
                  </div>
                  <div className="h-2 bg-muted rounded-full overflow-hidden">
                    <div
                      className={cn('h-full rounded-full transition-all duration-500', getConfidenceBarColor(classification.confidence))}
                      style={{ width: `${classification.confidence * 100}%` }}
                    />
                  </div>
                </div>
              </div>

              {classification.reasons.length > 0 && (
                <div className="space-y-2">
                  <div className="flex items-center gap-1.5 text-sm font-medium">
                    <Info className="h-4 w-4 text-muted-foreground" />
                    <span>分类理由</span>
                  </div>
                  <ul className="space-y-1.5">
                    {classification.reasons.map((reason, index) => (
                      <li
                        key={index}
                        className="flex items-start gap-2 text-sm text-muted-foreground"
                      >
                        <span className="text-primary mt-0.5">•</span>
                        <span>{reason}</span>
                      </li>
                    ))}
                  </ul>
                </div>
              )}

              {classification.matchedKeywords.length > 0 && (
                <div className="space-y-2">
                  <span className="text-sm font-medium">匹配的关键词</span>
                  <div className="flex flex-wrap gap-1.5">
                    {classification.matchedKeywords.map((keyword) => (
                      <span
                        key={keyword}
                        className="px-2 py-0.5 text-xs bg-muted rounded-md"
                      >
                        {keyword}
                      </span>
                    ))}
                  </div>
                </div>
              )}

              <div className="pt-2 border-t">
                <button
                  type="button"
                  onClick={() => setShowAllScores(!showAllScores)}
                  className="text-sm text-muted-foreground hover:text-foreground transition-colors flex items-center gap-1"
                >
                  {showAllScores ? '隐藏' : '显示'}所有类型评分
                  {showAllScores ? (
                    <ChevronUp className="h-3 w-3" />
                  ) : (
                    <ChevronDown className="h-3 w-3" />
                  )}
                </button>

                {showAllScores && (
                  <div className="mt-3 space-y-2">
                    {allTypes.map((type) => {
                      const score = classification.allScores[type] || 0;
                      const isBest = type === classification.type;
                      return (
                        <div
                          key={type}
                          className={cn(
                            'flex items-center gap-3 p-2 rounded-md',
                            isBest && 'bg-primary/5'
                          )}
                        >
                          <span
                            className="h-3 w-3 rounded-full flex-shrink-0"
                            style={{ backgroundColor: DocumentTypeColors[type] }}
                          />
                          <span className="text-sm flex-1">
                            {DocumentTypeLabels[type]}
                          </span>
                          <div className="w-24 h-1.5 bg-muted rounded-full overflow-hidden">
                            <div
                              className="h-full rounded-full transition-all"
                              style={{
                                width: `${score * 100}%`,
                                backgroundColor: isBest
                                  ? DocumentTypeColors[type]
                                  : '#9ca3af',
                              }}
                            />
                          </div>
                          <span
                            className={cn(
                              'text-xs w-8 text-right',
                              isBest ? 'font-medium text-primary' : 'text-muted-foreground'
                            )}
                          >
                            {Math.round(score * 100)}%
                          </span>
                        </div>
                      );
                    })}
                  </div>
                )}
              </div>
            </>
          ) : (
            <div className="text-center py-8 text-muted-foreground">
              <Sparkles className="h-8 w-8 mx-auto mb-2 opacity-50" />
              <p className="text-sm">暂无分类结果</p>
              {onReclassify && (
                <button
                  type="button"
                  onClick={onReclassify}
                  className="mt-2 text-sm text-primary hover:underline"
                >
                  开始分类
                </button>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
