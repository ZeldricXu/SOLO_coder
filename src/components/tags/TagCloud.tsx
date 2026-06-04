'use client';

import { useState, useMemo } from 'react';
import { TrendingUp, TrendingDown, Minus } from 'lucide-react';
import { cn } from '@/lib/utils';

interface TagCloudTag {
  id: string;
  name: string;
  color: string | null;
  count: number;
  trend?: 'up' | 'down' | 'stable';
}

interface TagCloudProps {
  tags: TagCloudTag[];
  selectedTagIds?: string[];
  onTagClick?: (tag: TagCloudTag) => void;
  minSize?: number;
  maxSize?: number;
  showCount?: boolean;
  showTrend?: boolean;
  className?: string;
}

export function TagCloud({
  tags,
  selectedTagIds = [],
  onTagClick,
  minSize = 0.75,
  maxSize = 2,
  showCount = true,
  showTrend = false,
  className,
}: TagCloudProps) {
  const [hoveredTagId, setHoveredTagId] = useState<string | null>(null);

  const sortedTags = useMemo(() => {
    return [...tags].sort((a, b) => b.count - a.count);
  }, [tags]);

  const { maxCount, minCount } = useMemo(() => {
    if (sortedTags.length === 0) {
      return { maxCount: 0, minCount: 0 };
    }
    const counts = sortedTags.map((t) => t.count);
    return {
      maxCount: Math.max(...counts),
      minCount: Math.min(...counts),
    };
  }, [sortedTags]);

  const getTagSize = (count: number) => {
    if (maxCount === minCount) {
      return (minSize + maxSize) / 2;
    }
    const normalized = (count - minCount) / (maxCount - minCount);
    return minSize + normalized * (maxSize - minSize);
  };

  const getContrastColor = (hexColor: string) => {
    const r = parseInt(hexColor.slice(1, 3), 16);
    const g = parseInt(hexColor.slice(3, 5), 16);
    const b = parseInt(hexColor.slice(5, 7), 16);
    const luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
    return luminance > 0.5 ? '#000000' : '#ffffff';
  };

  const getTrendIcon = (trend?: 'up' | 'down' | 'stable') => {
    if (!trend) return null;
    switch (trend) {
      case 'up':
        return <TrendingUp className="h-3 w-3 text-green-500" />;
      case 'down':
        return <TrendingDown className="h-3 w-3 text-red-500" />;
      case 'stable':
        return <Minus className="h-3 w-3 text-gray-500" />;
    }
  };

  if (tags.length === 0) {
    return (
      <div className={cn('text-center py-8 text-muted-foreground', className)}>
        <p className="text-sm">暂无标签</p>
      </div>
    );
  }

  return (
    <div className={cn('flex flex-wrap gap-2 justify-center items-center p-4', className)}>
      {sortedTags.map((tag) => {
        const size = getTagSize(tag.count);
        const isSelected = selectedTagIds.includes(tag.id);
        const isHovered = hoveredTagId === tag.id;
        const tagColor = tag.color || '#6b7280';

        return (
          <button
            key={tag.id}
            type="button"
            onClick={() => onTagClick?.(tag)}
            onMouseEnter={() => setHoveredTagId(tag.id)}
            onMouseLeave={() => setHoveredTagId(null)}
            className={cn(
              'inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full font-medium transition-all duration-200',
              isSelected
                ? 'ring-2 ring-offset-2 ring-primary'
                : 'hover:scale-105'
            )}
            style={{
              backgroundColor: isSelected ? tagColor : `${tagColor}15`,
              color: isSelected ? getContrastColor(tagColor) : tagColor,
              fontSize: `${size}rem`,
              opacity: hoveredTagId && !isHovered ? 0.5 : 1,
            }}
          >
            <span>{tag.name}</span>
            {showCount && (
              <span
                className={cn(
                  'text-xs px-1.5 py-0.5 rounded-full',
                  isSelected ? 'bg-white/20' : 'bg-black/5'
                )}
              >
                {tag.count}
              </span>
            )}
            {showTrend && getTrendIcon(tag.trend)}
          </button>
        );
      })}
    </div>
  );
}

interface TrendingTagCloudProps {
  tags: Array<{
    id: string;
    name: string;
    color: string | null;
    usageCount: number;
    growthRate: number;
  }>;
  onTagClick?: (tag: {
    id: string;
    name: string;
    color: string | null;
    usageCount: number;
    growthRate: number;
  }) => void;
  className?: string;
}

export function TrendingTagCloud({
  tags,
  onTagClick,
  className,
}: TrendingTagCloudProps) {
  const sortedTags = useMemo(() => {
    return [...tags].sort((a, b) => b.growthRate - a.growthRate);
  }, [tags]);

  const maxGrowthRate = Math.max(...sortedTags.map((t) => Math.abs(t.growthRate)), 1);

  if (tags.length === 0) {
    return (
      <div className={cn('text-center py-8 text-muted-foreground', className)}>
        <p className="text-sm">暂无热门标签</p>
      </div>
    );
  }

  return (
    <div className={cn('space-y-2', className)}>
      {sortedTags.map((tag, index) => {
        const intensity = Math.min(Math.abs(tag.growthRate) / maxGrowthRate, 1);
        const isHot = index < 3;
        const isGrowing = tag.growthRate > 0;

        return (
          <button
            key={tag.id}
            type="button"
            onClick={() => onTagClick?.(tag)}
            className="w-full flex items-center gap-3 p-3 rounded-lg hover:bg-muted/50 transition-colors group"
          >
            <span
              className={cn(
                'w-6 h-6 rounded-full flex items-center justify-center text-xs font-bold',
                isHot ? 'bg-gradient-to-br from-orange-400 to-red-500 text-white' : 'bg-muted text-muted-foreground'
              )}
            >
              {index + 1}
            </span>

            <span
              className="h-3 w-3 rounded-full flex-shrink-0"
              style={{ backgroundColor: tag.color || '#6b7280' }}
            />

            <span className="flex-1 text-left font-medium text-sm">
              {tag.name}
              {isHot && (
                <span className="ml-1.5 text-orange-500 text-xs">🔥</span>
              )}
            </span>

            <div className="flex items-center gap-2">
              <span className="text-xs text-muted-foreground">
                {tag.usageCount} 次使用
              </span>
              <span
                className={cn(
                  'text-xs font-medium px-1.5 py-0.5 rounded',
                  isGrowing
                    ? 'text-green-600 bg-green-50'
                    : 'text-red-600 bg-red-50'
                )}
              >
                {isGrowing ? '+' : ''}
                {Math.round(tag.growthRate * 100)}%
              </span>
              <div
                className="w-16 h-1.5 bg-muted rounded-full overflow-hidden"
              >
                <div
                  className="h-full rounded-full transition-all"
                  style={{
                    width: `${intensity * 100}%`,
                    backgroundColor: isGrowing ? '#22c55e' : '#ef4444',
                    marginLeft: isGrowing ? 0 : 'auto',
                  }}
                />
              </div>
            </div>
          </button>
        );
      })}
    </div>
  );
}
