'use client';

import { useState } from 'react';
import * as Tabs from '@radix-ui/react-tabs';
import * as Label from '@radix-ui/react-label';
import {
  Settings,
  X,
  Save,
  Users,
  Share2,
  Trash2,
  Globe,
  Lock,
  Palette,
  AlertTriangle,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@radix-ui/react-input';
import { cn } from '@/lib/utils';
import { MemberList } from './MemberList';
import { ShareDialog } from './ShareDialog';
import { ROLE_LABELS, canManageRoles } from '@/lib/constants/permissions';
import type {
  SpaceWithMembers,
  SpaceMember,
  SpaceShareLink,
  UpdateSpaceInput,
} from '@/lib/types/space';
import type { Role } from '@/lib/types/permission';

interface SpaceSettingsProps {
  space: SpaceWithMembers;
  currentUserId: string;
  currentUserRole?: Role;
  shareLinks: SpaceShareLink[];
  onUpdate: (input: UpdateSpaceInput) => void;
  onDelete: () => void;
  onAddMember: (email: string, role: Role) => void;
  onUpdateMemberRole: (userId: string, role: Role) => void;
  onRemoveMember: (userId: string) => void;
  onCreateShareLink: (input: {
    password?: string;
    expiresAt?: Date;
    role?: Role;
  }) => void;
  onRevokeShareLink: (linkId: string) => void;
  isLoading?: boolean;
  onClose: () => void;
}

const COLORS = [
  { value: 'bg-blue-500', label: '蓝色' },
  { value: 'bg-green-500', label: '绿色' },
  { value: 'bg-yellow-500', label: '黄色' },
  { value: 'bg-red-500', label: '红色' },
  { value: 'bg-purple-500', label: '紫色' },
  { value: 'bg-pink-500', label: '粉色' },
  { value: 'bg-indigo-500', label: '靛蓝' },
  { value: 'bg-cyan-500', label: '青色' },
];

const ICONS = ['📚', '📁', '💼', '🎯', '🚀', '💡', '📝', '🎨', '🔧', '📊', '🎓', '🌟'];

export function SpaceSettings({
  space,
  currentUserId,
  currentUserRole,
  shareLinks,
  onUpdate,
  onDelete,
  onAddMember,
  onUpdateMemberRole,
  onRemoveMember,
  onCreateShareLink,
  onRevokeShareLink,
  isLoading = false,
  onClose,
}: SpaceSettingsProps) {
  const [name, setName] = useState(space.name);
  const [description, setDescription] = useState(space.description ?? '');
  const [icon, setIcon] = useState(space.icon ?? '');
  const [color, setColor] = useState(space.color ?? 'bg-blue-500');
  const [visibility, setVisibility] = useState(space.visibility);
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false);
  const [showShareDialog, setShowShareDialog] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [hasChanges, setHasChanges] = useState(false);

  const canManage = currentUserRole && canManageRoles.includes(currentUserRole);
  const isOwner = currentUserRole === 'OWNER';

  const handleFieldChange = (
    setter: React.Dispatch<React.SetStateAction<any>>,
    value: any
  ) => {
    setter(value);
    setHasChanges(true);
  };

  const validate = (): boolean => {
    const newErrors: Record<string, string> = {};

    if (!name.trim()) {
      newErrors.name = '空间名称不能为空';
    } else if (name.length > 100) {
      newErrors.name = '空间名称最多100个字符';
    }

    if (description.length > 500) {
      newErrors.description = '描述最多500个字符';
    }

    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  const handleSave = (e: React.FormEvent) => {
    e.preventDefault();

    if (!validate()) return;

    onUpdate({
      id: space.id,
      name: name.trim(),
      description: description.trim() || undefined,
      icon: icon || undefined,
      color,
      visibility,
    });

    setHasChanges(false);
  };

  const handleDelete = () => {
    if (showDeleteConfirm) {
      onDelete();
    } else {
      setShowDeleteConfirm(true);
    }
  };

  return (
    <div className="fixed inset-0 bg-black/50 backdrop-blur-sm z-50 flex items-center justify-center p-4">
      <div className="bg-background rounded-xl shadow-xl w-full max-w-4xl max-h-[90vh] overflow-hidden flex flex-col">
        <div className="flex items-center justify-between p-6 border-b">
          <div className="flex items-center gap-3">
            <div
              className={cn(
                'flex h-10 w-10 items-center justify-center rounded-lg text-white text-lg font-bold',
                color
              )}
            >
              {icon || name.charAt(0).toUpperCase()}
            </div>
            <div>
              <h2 className="text-xl font-semibold flex items-center gap-2">
                <Settings className="h-5 w-5" />
                空间设置
              </h2>
              <p className="text-sm text-muted-foreground">
                管理 &quot;{space.name}&quot; 的设置
              </p>
            </div>
          </div>
          <Button
            type="button"
            variant="ghost"
            size="sm"
            onClick={onClose}
            className="h-9 w-9 p-0 rounded-md hover:bg-muted"
          >
            <X className="h-5 w-5" />
          </Button>
        </div>

        <Tabs.Root defaultValue="general" className="flex-1 flex flex-col overflow-hidden">
          <Tabs.List className="flex border-b px-6 gap-1">
            <Tabs.Trigger
              value="general"
              className={cn(
                'px-4 py-3 text-sm font-medium transition-colors relative',
                'data-[state=active]:text-primary',
                'data-[state=active]:after:absolute data-[state=active]:after:bottom-0 data-[state=active]:after:left-0 data-[state=active]:after:right-0 data-[state=active]:after:h-0.5 data-[state=active]:after:bg-primary'
              )}
            >
              <span className="flex items-center gap-2">
                <Palette className="h-4 w-4" />
                基本信息
              </span>
            </Tabs.Trigger>
            <Tabs.Trigger
              value="members"
              className={cn(
                'px-4 py-3 text-sm font-medium transition-colors relative',
                'data-[state=active]:text-primary',
                'data-[state=active]:after:absolute data-[state=active]:after:bottom-0 data-[state=active]:after:left-0 data-[state=active]:after:right-0 data-[state=active]:after:h-0.5 data-[state=active]:after:bg-primary'
              )}
            >
              <span className="flex items-center gap-2">
                <Users className="h-4 w-4" />
                成员 ({space.members.length})
              </span>
            </Tabs.Trigger>
            <Tabs.Trigger
              value="sharing"
              className={cn(
                'px-4 py-3 text-sm font-medium transition-colors relative',
                'data-[state=active]:text-primary',
                'data-[state=active]:after:absolute data-[state=active]:after:bottom-0 data-[state=active]:after:left-0 data-[state=active]:after:right-0 data-[state=active]:after:h-0.5 data-[state=active]:after:bg-primary'
              )}
            >
              <span className="flex items-center gap-2">
                <Share2 className="h-4 w-4" />
                分享
              </span>
            </Tabs.Trigger>
            {isOwner && (
              <Tabs.Trigger
                value="danger"
                className={cn(
                  'px-4 py-3 text-sm font-medium transition-colors relative ml-auto',
                  'data-[state=active]:text-destructive',
                  'data-[state=active]:after:absolute data-[state=active]:after:bottom-0 data-[state=active]:after:left-0 data-[state=active]:after:right-0 data-[state=active]:after:h-0.5 data-[state=active]:after:bg-destructive'
                )}
              >
                <span className="flex items-center gap-2">
                  <AlertTriangle className="h-4 w-4" />
                  危险操作
                </span>
              </Tabs.Trigger>
            )}
          </Tabs.List>

          <div className="flex-1 overflow-y-auto p-6">
            <Tabs.Content value="general" className="outline-none">
              <form onSubmit={handleSave} className="space-y-6 max-w-2xl">
                <div className="flex items-start gap-6">
                  <div
                    className={cn(
                      'flex h-20 w-20 items-center justify-center rounded-xl text-white text-3xl font-bold flex-shrink-0',
                      color
                    )}
                  >
                    {icon || name.charAt(0).toUpperCase() || '?'}
                  </div>
                  <div className="flex-1">
                    <Label.Root className="text-sm font-medium mb-1.5 block">
                      空间名称 <span className="text-destructive">*</span>
                    </Label.Root>
                    <Input
                      type="text"
                      value={name}
                      onChange={(e) => handleFieldChange(setName, e.target.value)}
                      placeholder="输入空间名称"
                      className={cn(
                        'h-10 w-full bg-background border rounded-md px-3 text-sm',
                        errors.name && 'border-destructive'
                      )}
                      disabled={!canManage || isLoading}
                    />
                    {errors.name && (
                      <p className="text-xs text-destructive mt-1">{errors.name}</p>
                    )}
                  </div>
                </div>

                {canManage && (
                  <>
                    <div>
                      <Label.Root className="text-sm font-medium mb-1.5 block">
                        图标
                      </Label.Root>
                      <div className="flex flex-wrap gap-2">
                        {ICONS.map((i) => (
                          <button
                            key={i}
                            type="button"
                            onClick={() => handleFieldChange(setIcon, icon === i ? '' : i)}
                            className={cn(
                              'h-10 w-10 rounded-lg text-xl flex items-center justify-center transition-all',
                              icon === i
                                ? 'bg-primary text-primary-foreground scale-110'
                                : 'bg-muted hover:bg-muted/80'
                            )}
                            disabled={isLoading}
                          >
                            {i}
                          </button>
                        ))}
                      </div>
                    </div>

                    <div>
                      <Label.Root className="text-sm font-medium mb-1.5 block">
                        颜色
                      </Label.Root>
                      <div className="flex flex-wrap gap-2">
                        {COLORS.map((c) => (
                          <button
                            key={c.value}
                            type="button"
                            onClick={() => handleFieldChange(setColor, c.value)}
                            className={cn(
                              'h-8 w-8 rounded-full transition-all',
                              c.value,
                              color === c.value && 'ring-2 ring-offset-2 ring-primary scale-110'
                            )}
                            title={c.label}
                            disabled={isLoading}
                          />
                        ))}
                      </div>
                    </div>
                  </>
                )}

                <div>
                  <Label.Root className="text-sm font-medium mb-1.5 block">
                    描述
                  </Label.Root>
                  <textarea
                    value={description}
                    onChange={(e) => handleFieldChange(setDescription, e.target.value)}
                    placeholder="描述一下这个空间的用途..."
                    rows={3}
                    className={cn(
                      'w-full bg-background border rounded-md px-3 py-2 text-sm resize-none',
                      errors.description && 'border-destructive',
                      !canManage && 'opacity-50 cursor-not-allowed'
                    )}
                    disabled={!canManage || isLoading}
                  />
                  {errors.description && (
                    <p className="text-xs text-destructive mt-1">{errors.description}</p>
                  )}
                  <p className="text-xs text-muted-foreground mt-1 text-right">
                    {description.length}/500
                  </p>
                </div>

                {canManage && (
                  <div>
                    <Label.Root className="text-sm font-medium mb-1.5 block">
                      可见性
                    </Label.Root>
                    <div className="grid grid-cols-2 gap-3">
                      <button
                        type="button"
                        onClick={() => handleFieldChange(setVisibility, 'PRIVATE')}
                        className={cn(
                          'flex flex-col items-center gap-2 p-4 rounded-lg border-2 transition-all text-left',
                          visibility === 'PRIVATE'
                            ? 'border-primary bg-primary/5'
                            : 'border-muted hover:border-muted-foreground/30'
                        )}
                        disabled={isLoading}
                      >
                        <Lock className="h-5 w-5" />
                        <div>
                          <div className="font-medium text-sm">私有</div>
                          <div className="text-xs text-muted-foreground">
                            仅邀请成员可访问
                          </div>
                        </div>
                      </button>
                      <button
                        type="button"
                        onClick={() => handleFieldChange(setVisibility, 'PUBLIC')}
                        className={cn(
                          'flex flex-col items-center gap-2 p-4 rounded-lg border-2 transition-all text-left',
                          visibility === 'PUBLIC'
                            ? 'border-primary bg-primary/5'
                            : 'border-muted hover:border-muted-foreground/30'
                        )}
                        disabled={isLoading}
                      >
                        <Globe className="h-5 w-5" />
                        <div>
                          <div className="font-medium text-sm">公开</div>
                          <div className="text-xs text-muted-foreground">
                            知道链接即可访问
                          </div>
                        </div>
                      </button>
                    </div>
                  </div>
                )}

                {canManage && (
                  <div className="flex justify-end gap-3 pt-4 border-t">
                    <Button
                      type="button"
                      variant="secondary"
                      onClick={onClose}
                      className="h-10 px-4 bg-secondary text-secondary-foreground hover:bg-secondary/80 rounded-md inline-flex items-center justify-center text-sm font-medium transition-colors"
                      disabled={isLoading}
                    >
                      取消
                    </Button>
                    <Button
                      type="submit"
                      className="h-10 px-4 bg-primary text-primary-foreground hover:bg-primary/90 rounded-md inline-flex items-center justify-center gap-2 text-sm font-medium transition-colors"
                      disabled={isLoading || !hasChanges}
                    >
                      <Save className="h-4 w-4" />
                      {isLoading ? '保存中...' : '保存更改'}
                    </Button>
                  </div>
                )}
              </form>
            </Tabs.Content>

            <Tabs.Content value="members" className="outline-none">
              <MemberList
                members={space.members as unknown as SpaceMember[]}
                currentUserId={currentUserId}
                currentUserRole={currentUserRole}
                onAddMember={onAddMember}
                onUpdateRole={onUpdateMemberRole}
                onRemoveMember={onRemoveMember}
                isLoading={isLoading}
              />
            </Tabs.Content>

            <Tabs.Content value="sharing" className="outline-none">
              <div className="space-y-6">
                <div className="flex items-center justify-between">
                  <div>
                    <h3 className="text-lg font-semibold flex items-center gap-2">
                      <Share2 className="h-5 w-5" />
                      分享设置
                    </h3>
                    <p className="text-sm text-muted-foreground">
                      管理空间的分享链接和访问权限
                    </p>
                  </div>
                  {canManage && (
                    <Button
                      type="button"
                      onClick={() => setShowShareDialog(true)}
                      className="h-10 px-4 bg-primary text-primary-foreground hover:bg-primary/90 rounded-md inline-flex items-center justify-center gap-2 text-sm font-medium transition-colors"
                      disabled={isLoading}
                    >
                      <Share2 className="h-4 w-4" />
                      创建分享链接
                    </Button>
                  )}
                </div>

                <div className="p-4 bg-muted/50 rounded-lg">
                  <div className="flex items-center gap-3">
                    <div
                      className={cn(
                        'p-2 rounded-md',
                        visibility === 'PUBLIC'
                          ? 'bg-green-100 text-green-700'
                          : 'bg-gray-100 text-gray-700'
                      )}
                    >
                      {visibility === 'PUBLIC' ? (
                        <Globe className="h-5 w-5" />
                      ) : (
                        <Lock className="h-5 w-5" />
                      )}
                    </div>
                    <div>
                      <div className="font-medium text-sm">
                        当前状态：{visibility === 'PUBLIC' ? '公开' : '私有'}
                      </div>
                      <div className="text-xs text-muted-foreground">
                        {visibility === 'PUBLIC'
                          ? '任何拥有链接的人都可以访问'
                          : '只有被邀请的成员才能访问'}
                      </div>
                    </div>
                  </div>
                </div>

                {shareLinks.length > 0 && (
                  <div>
                    <h4 className="font-medium text-sm mb-3">
                      活跃的分享链接 ({shareLinks.length})
                    </h4>
                    <div className="space-y-2">
                      {shareLinks.slice(0, 5).map((link) => (
                        <div
                          key={link.id}
                          className="flex items-center justify-between p-3 rounded-lg border"
                        >
                          <div className="flex items-center gap-3">
                            <div className="p-2 rounded-md bg-primary/10 text-primary">
                              <Share2 className="h-4 w-4" />
                            </div>
                            <div>
                              <div className="text-sm font-medium">
                                {ROLE_LABELS[link.role]} 访问
                              </div>
                              <div className="text-xs text-muted-foreground">
                                创建于 {new Date(link.createdAt).toLocaleDateString('zh-CN')}
                              </div>
                            </div>
                          </div>
                          <Button
                            type="button"
                            variant="ghost"
                            size="sm"
                            onClick={() => setShowShareDialog(true)}
                            className="h-8 px-3 text-sm rounded-md hover:bg-muted"
                          >
                            管理
                          </Button>
                        </div>
                      ))}
                    </div>
                  </div>
                )}
              </div>
            </Tabs.Content>

            {isOwner && (
              <Tabs.Content value="danger" className="outline-none">
                <div className="space-y-6 max-w-2xl">
                  <div className="p-6 border border-destructive/50 bg-destructive/5 rounded-lg">
                    <h3 className="text-lg font-semibold text-destructive flex items-center gap-2 mb-2">
                      <AlertTriangle className="h-5 w-5" />
                      删除空间
                    </h3>
                    <p className="text-sm text-muted-foreground mb-4">
                      删除空间将永久移除所有文档、评论和成员关系。此操作不可撤销。
                    </p>

                    {showDeleteConfirm ? (
                      <div className="space-y-4">
                        <div className="p-4 bg-background rounded-md border">
                          <p className="text-sm font-medium mb-2">
                            此操作将永久删除：
                          </p>
                          <ul className="text-sm text-muted-foreground space-y-1 list-disc list-inside">
                            <li>{space._count.documents} 个文档及其所有版本</li>
                            <li>{space._count.members} 位成员的访问权限</li>
                            <li>所有评论、标签和分享链接</li>
                          </ul>
                        </div>
                        <p className="text-sm">
                          请输入 <span className="font-mono bg-muted px-1.5 py-0.5 rounded">{space.name}</span> 以确认删除：
                        </p>
                        <div className="flex gap-3">
                          <Input
                            type="text"
                            placeholder={`输入 "${space.name}" 确认`}
                            id="delete-confirm"
                            className="flex-1 h-10 bg-background border rounded-md px-3 text-sm"
                          />
                          <Button
                            type="button"
                            variant="destructive"
                            onClick={handleDelete}
                            disabled={isLoading}
                            className="h-10 px-4 bg-destructive text-destructive-foreground hover:bg-destructive/90 rounded-md inline-flex items-center justify-center gap-2 text-sm font-medium transition-colors"
                          >
                            <Trash2 className="h-4 w-4" />
                            确认删除
                          </Button>
                        </div>
                        <Button
                          type="button"
                          variant="ghost"
                          onClick={() => setShowDeleteConfirm(false)}
                          className="text-sm h-9 px-3 rounded-md hover:bg-muted"
                        >
                          取消
                        </Button>
                      </div>
                    ) : (
                      <Button
                        type="button"
                        variant="destructive"
                        onClick={handleDelete}
                        disabled={isLoading}
                        className="h-10 px-4 bg-destructive text-destructive-foreground hover:bg-destructive/90 rounded-md inline-flex items-center justify-center gap-2 text-sm font-medium transition-colors"
                      >
                        <Trash2 className="h-4 w-4" />
                        删除空间
                      </Button>
                    )}
                  </div>
                </div>
              </Tabs.Content>
            )}
          </div>
        </Tabs.Root>
      </div>

      <ShareDialog
        open={showShareDialog}
        onOpenChange={setShowShareDialog}
        space={space}
        shareLinks={shareLinks}
        onCreateLink={onCreateShareLink}
        onRevokeLink={onRevokeShareLink}
        isLoading={isLoading}
      />
    </div>
  );
}
