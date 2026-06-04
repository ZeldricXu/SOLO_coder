'use client';

import * as React from 'react';
import { cn, getInitials } from '@/lib/utils';
import {
  Avatar,
  AvatarFallback,
  AvatarImage,
} from '@/components/ui/avatar';
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from '@/components/ui/tooltip';

interface UserAvatarProps extends React.HTMLAttributes<HTMLDivElement> {
  name?: string;
  email?: string;
  avatarUrl?: string;
  size?: 'sm' | 'md' | 'lg';
  showTooltip?: boolean;
}

const sizeClasses = {
  sm: 'h-8 w-8 text-xs',
  md: 'h-10 w-10 text-sm',
  lg: 'h-12 w-12 text-base',
};

function UserAvatar({
  name,
  email,
  avatarUrl,
  size = 'md',
  showTooltip = false,
  className,
  ...props
}: UserAvatarProps) {
  const displayName = name || email || '?';
  const initials = getInitials(displayName);
  const avatarElement = (
    <Avatar className={cn(sizeClasses[size], className)} {...props}>
      {avatarUrl && <AvatarImage src={avatarUrl} alt={displayName} />}
      <AvatarFallback
        className={cn(
          'font-medium',
          size === 'sm' && 'text-xs',
          size === 'md' && 'text-sm',
          size === 'lg' && 'text-base'
        )}
      >
        {initials}
      </AvatarFallback>
    </Avatar>
  );

  if (showTooltip && (name || email)) {
    return (
      <Tooltip>
        <TooltipTrigger asChild>{avatarElement}</TooltipTrigger>
        <TooltipContent>
          <p>{name || email}</p>
          {name && email && <p className="text-xs text-muted-foreground">{email}</p>}
        </TooltipContent>
      </Tooltip>
    );
  }

  return avatarElement;
}

export { UserAvatar };
