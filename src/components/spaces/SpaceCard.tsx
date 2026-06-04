'use client';

import { Users, FileText, Globe, Lock } from 'lucide-react';
import { cn } from '@/lib/utils';
import { formatTimeAgo } from '@/lib/utils';
import { ROLE_LABELS } from '@/lib/constants/permissions';
import type { SpaceWithOwner } from '@/lib/types/space';
import type { Role } from '@/lib/types/permission';

interface SpaceCardProps {
  space: SpaceWithOwner;
  userRole?: Role;
  onClick?: () => void;
  className?: string;
}

export function SpaceCard({ space, userRole, onClick, className }: SpaceCardProps) {
  const isPublic = space.visibility === 'PUBLIC';
  const defaultColors = [
    'bg-blue-500',
    'bg-green-500',
    'bg-yellow-500',
    'bg-red-500',
    'bg-purple-500',
    'bg-pink-500',
    'bg-indigo-500',
    'bg-cyan-500',
  ];
  const colorIndex = space.name.charCodeAt(0) % defaultColors.length;
  const bgColor = space.color || defaultColors[colorIndex];

  return (
    <div
      onClick={onClick}
      className={cn(
        'group relative overflow-hidden rounded-xl border bg-card p-5 transition-all hover:shadow-lg hover:-translate-y-1 cursor-pointer',
        className
      )}
    >
      <div className="flex items-start justify-between mb-4">
        <div
          className={cn(
            'flex h-12 w-12 items-center justify-center rounded-lg text-white text-xl font-bold',
            bgColor
          )}
        >
          {space.icon || space.name.charAt(0).toUpperCase()}
        </div>
        <div className="flex items-center gap-1.5">
          {isPublic ? (
            <span className="flex items-center gap-1 text-xs text-muted-foreground">
              <Globe className="h-3.5 w-3.5" />
              公开
            </span>
          ) : (
            <span className="flex items-center gap-1 text-xs text-muted-foreground">
              <Lock className="h-3.5 w-3.5" />
              私有
            </span>
          )}
        </div>
      </div>

      <div className="space-y-2">
        <h3 className="font-semibold text-lg leading-tight truncate">
          {space.name}
        </h3>
        {space.description && (
          <p className="text-sm text-muted-foreground line-clamp-2 min-h-[40px]">
            {space.description}
          </p>
        )}
      </div>

      <div className="mt-4 flex items-center justify-between text-sm text-muted-foreground">
        <div className="flex items-center gap-3">
          <span className="flex items-center gap-1">
            <Users className="h-4 w-4" />
            {space._count.members}
          </span>
          <span className="flex items-center gap-1">
            <FileText className="h-4 w-4" />
            {space._count.documents}
          </span>
        </div>
        {userRole && (
          <span className="text-xs px-2 py-0.5 bg-muted rounded-full">
            {ROLE_LABELS[userRole]}
          </span>
        )}
      </div>

      <div className="mt-3 pt-3 border-t flex items-center justify-between text-xs text-muted-foreground">
        <span>
          创建者：{space.createdBy.name}
        </span>
        <span>
          更新于 {formatTimeAgo(space.updatedAt)}
        </span>
      </div>

      <div className="absolute inset-x-0 bottom-0 h-1 bg-gradient-to-r from-primary/50 to-primary opacity-0 group-hover:opacity-100 transition-opacity" />
    </div>
  );
}
