'use client';

import { useState } from 'react';
import * as Select from '@radix-ui/react-select';
import * as Label from '@radix-ui/react-label';
import { Users, UserPlus, Trash2, ChevronDown, Check, Crown } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@radix-ui/react-input';
import { cn } from '@/lib/utils';
import { ROLE_LABELS, canManageRoles } from '@/lib/constants/permissions';
import { formatTimeAgo } from '@/lib/utils';
import type { SpaceMember } from '@/lib/types/space';
import type { Role } from '@/lib/types/permission';

interface MemberListProps {
  members: SpaceMember[];
  currentUserId: string;
  currentUserRole?: Role;
  onAddMember: (email: string, role: Role) => void;
  onUpdateRole: (userId: string, role: Role) => void;
  onRemoveMember: (userId: string) => void;
  isLoading?: boolean;
}

export function MemberList({
  members,
  currentUserId,
  currentUserRole,
  onAddMember,
  onUpdateRole,
  onRemoveMember,
  isLoading = false,
}: MemberListProps) {
  const [newMemberEmail, setNewMemberEmail] = useState('');
  const [newMemberRole, setNewMemberRole] = useState<Role>('VIEWER');
  const [errors, setErrors] = useState<Record<string, string>>({});

  const canManage = currentUserRole && canManageRoles.includes(currentUserRole);

  const handleAddMember = (e: React.FormEvent) => {
    e.preventDefault();

    if (!newMemberEmail.trim()) {
      setErrors({ email: '请输入用户邮箱' });
      return;
    }

    const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
    if (!emailRegex.test(newMemberEmail)) {
      setErrors({ email: '邮箱格式不正确' });
      return;
    }

    setErrors({});
    onAddMember(newMemberEmail.trim(), newMemberRole);
    setNewMemberEmail('');
    setNewMemberRole('VIEWER');
  };

  const handleRoleChange = (userId: string, newRole: Role) => {
    onUpdateRole(userId, newRole);
  };

  const handleRemoveMember = (userId: string) => {
    if (confirm('确定要移除该成员吗？')) {
      onRemoveMember(userId);
    }
  };

  const sortedMembers = [...members].sort((a, b) => {
    const roleOrder: Record<Role, number> = { OWNER: 0, ADMIN: 1, EDITOR: 2, VIEWER: 3 };
    if (roleOrder[a.role] !== roleOrder[b.role]) {
      return roleOrder[a.role] - roleOrder[b.role];
    }
    return new Date(a.joinedAt).getTime() - new Date(b.joinedAt).getTime();
  });

  const RoleSelect = ({
    value,
    onChange,
    disabled,
    isCurrentUser,
  }: {
    value: Role;
    onChange: (role: Role) => void;
    disabled?: boolean;
    isCurrentUser?: boolean;
  }) => (
    <Select.Root
      value={value}
      onValueChange={(val) => onChange(val as Role)}
      disabled={disabled || isCurrentUser}
    >
      <Select.Trigger
        className={cn(
          'inline-flex items-center justify-between rounded-md border bg-background px-3 py-1.5 text-sm h-8 min-w-[100px]',
          'focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2',
          'disabled:opacity-50 disabled:cursor-not-allowed',
          value === 'OWNER' && 'bg-yellow-50 border-yellow-200 text-yellow-700'
        )}
      >
        <Select.Value />
        <Select.Icon>
          <ChevronDown className="h-4 w-4 opacity-50" />
        </Select.Icon>
      </Select.Trigger>
      <Select.Portal>
        <Select.Content className="overflow-hidden bg-background rounded-md border shadow-lg z-50 min-w-[120px]">
          <Select.Viewport className="p-1">
            {(['VIEWER', 'EDITOR', 'ADMIN'] as Role[]).map((role) => (
              <Select.Item
                key={role}
                value={role}
                className="relative flex items-center px-3 py-2 text-sm rounded-sm cursor-pointer hover:bg-muted outline-none"
              >
                <Select.ItemText>{ROLE_LABELS[role]}</Select.ItemText>
              </Select.Item>
            ))}
          </Select.Viewport>
        </Select.Content>
      </Select.Portal>
    </Select.Root>
  );

  return (
    <div className="space-y-6">
      <div>
        <h3 className="text-lg font-semibold mb-2 flex items-center gap-2">
          <Users className="h-5 w-5" />
          成员管理
          <span className="text-sm font-normal text-muted-foreground">
            ({members.length} 位成员)
          </span>
        </h3>
        <p className="text-sm text-muted-foreground">
          管理空间成员及其访问权限
        </p>
      </div>

      {canManage && (
        <form onSubmit={handleAddMember} className="flex gap-3 p-4 bg-muted/50 rounded-lg">
          <div className="flex-1">
            <Label.Root className="text-sm font-medium mb-1.5 block sr-only">
              添加成员
            </Label.Root>
            <Input
              type="email"
              value={newMemberEmail}
              onChange={(e) => setNewMemberEmail(e.target.value)}
              placeholder="输入用户邮箱..."
              className={cn(
                'h-10 w-full bg-background border rounded-md px-3 text-sm',
                errors.email && 'border-destructive'
              )}
              disabled={isLoading}
            />
            {errors.email && (
              <p className="text-xs text-destructive mt-1">{errors.email}</p>
            )}
          </div>
          <RoleSelect
            value={newMemberRole}
            onChange={setNewMemberRole}
            disabled={isLoading}
          />
          <Button
            type="submit"
            className="h-10 px-4 bg-primary text-primary-foreground hover:bg-primary/90 rounded-md inline-flex items-center justify-center gap-2 text-sm font-medium transition-colors"
            disabled={isLoading}
          >
            <UserPlus className="h-4 w-4" />
            添加
          </Button>
        </form>
      )}

      <div className="divide-y rounded-lg border overflow-hidden">
        {sortedMembers.map((member) => {
          const isCurrentUser = member.userId === currentUserId;
          const isOwner = member.role === 'OWNER';
          const canEditRole =
            canManage &&
            !isOwner &&
            !isCurrentUser &&
            currentUserRole &&
            (currentUserRole === 'OWNER' ||
              (currentUserRole === 'ADMIN' && member.role !== 'ADMIN'));

          return (
            <div
              key={member.id}
              className={cn(
                'flex items-center gap-4 p-4 hover:bg-muted/50 transition-colors',
                isCurrentUser && 'bg-primary/5'
              )}
            >
              <div className="flex-shrink-0">
                {member.user.avatar ? (
                  <img
                    src={member.user.avatar}
                    alt={member.user.name}
                    className="h-10 w-10 rounded-full object-cover"
                  />
                ) : (
                  <div className="h-10 w-10 rounded-full bg-muted flex items-center justify-center text-sm font-medium">
                    {member.user.name.charAt(0).toUpperCase()}
                  </div>
                )}
              </div>

              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2">
                  <span className="font-medium truncate">
                    {member.user.name}
                    {isCurrentUser && (
                      <span className="text-xs text-muted-foreground">(你)</span>
                    )}
                  </span>
                  {isOwner && <Crown className="h-4 w-4 text-yellow-500" />}
                </div>
                <div className="text-sm text-muted-foreground truncate">
                  {member.user.email}
                </div>
                <div className="text-xs text-muted-foreground mt-0.5">
                  加入于 {formatTimeAgo(member.joinedAt)}
                </div>
              </div>

              <div className="flex items-center gap-2">
                <RoleSelect
                  value={member.role}
                  onChange={(role) => handleRoleChange(member.userId, role)}
                  disabled={!canEditRole}
                  isCurrentUser={isCurrentUser}
                />
                {canManage && !isOwner && !isCurrentUser && (
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    onClick={() => handleRemoveMember(member.userId)}
                    className="h-8 w-8 p-0 text-destructive hover:bg-destructive/10 hover:text-destructive rounded-md"
                    disabled={isLoading}
                    title="移除成员"
                  >
                    <Trash2 className="h-4 w-4" />
                  </Button>
                )}
              </div>
            </div>
          );
        })}

        {sortedMembers.length === 0 && (
          <div className="p-8 text-center text-muted-foreground">
            <Users className="h-12 w-12 mx-auto mb-3 opacity-50" />
            <p>暂无成员</p>
          </div>
        )}
      </div>
    </div>
  );
}
