'use client';

import { useState } from 'react';
import * as Dialog from '@radix-ui/react-dialog';
import * as Select from '@radix-ui/react-select';
import * as Label from '@radix-ui/react-label';
import {
  Share2,
  X,
  Copy,
  Check,
  Link,
  Clock,
  Lock,
  Eye,
  EyeOff,
  Trash2,
  ChevronDown,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@radix-ui/react-input';
import { cn } from '@/lib/utils';
import { ROLE_LABELS } from '@/lib/constants/permissions';
import { formatTimeAgo } from '@/lib/utils';
import type { SpaceShareLink, SpaceBasic } from '@/lib/types/space';
import type { Role } from '@/lib/types/permission';

interface ShareDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  space: SpaceBasic;
  shareLinks: SpaceShareLink[];
  onCreateLink: (input: {
    password?: string;
    expiresAt?: Date;
    role?: Role;
  }) => void;
  onRevokeLink: (linkId: string) => void;
  isLoading?: boolean;
}

const EXPIRY_OPTIONS = [
  { label: '永不过期', value: null },
  { label: '1小时', value: 1 },
  { label: '1天', value: 24 },
  { label: '7天', value: 168 },
  { label: '30天', value: 720 },
];

export function ShareDialog({
  open,
  onOpenChange,
  space,
  shareLinks,
  onCreateLink,
  onRevokeLink,
  isLoading = false,
}: ShareDialogProps) {
  const [copiedId, setCopiedId] = useState<string | null>(null);
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [expiryHours, setExpiryHours] = useState<number | null>(null);
  const [linkRole, setLinkRole] = useState<Role>('VIEWER');
  const [errors, setErrors] = useState<Record<string, string>>({});

  const resetForm = () => {
    setPassword('');
    setShowPassword(false);
    setExpiryHours(null);
    setLinkRole('VIEWER');
    setErrors({});
  };

  const handleOpenChange = (newOpen: boolean) => {
    if (!newOpen) {
      resetForm();
    }
    onOpenChange(newOpen);
  };

  const copyToClipboard = async (text: string, id: string) => {
    await navigator.clipboard.writeText(text);
    setCopiedId(id);
    setTimeout(() => setCopiedId(null), 2000);
  };

  const handleCreateLink = (e: React.FormEvent) => {
    e.preventDefault();

    if (password && password.length < 6) {
      setErrors({ password: '密码至少6个字符' });
      return;
    }

    setErrors({});

    const expiresAt = expiryHours
      ? new Date(Date.now() + expiryHours * 60 * 60 * 1000)
      : undefined;

    onCreateLink({
      password: password || undefined,
      expiresAt,
      role: linkRole,
    });

    resetForm();
  };

  const getShareUrl = (token: string) => {
    return `${window.location.origin}/share/${token}`;
  };

  const RoleSelect = ({
    value,
    onChange,
    disabled,
  }: {
    value: Role;
    onChange: (role: Role) => void;
    disabled?: boolean;
  }) => (
    <Select.Root
      value={value}
      onValueChange={(val) => onChange(val as Role)}
      disabled={disabled}
    >
      <Select.Trigger
        className={cn(
          'inline-flex items-center justify-between rounded-md border bg-background px-3 py-2 text-sm h-10 min-w-[120px]',
          'focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2',
          'disabled:opacity-50 disabled:cursor-not-allowed'
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
            {(['VIEWER', 'EDITOR'] as Role[]).map((role) => (
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
    <Dialog.Root open={open} onOpenChange={handleOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 bg-black/50 backdrop-blur-sm z-50" />
        <Dialog.Content className="fixed left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2 w-full max-w-2xl bg-background rounded-xl shadow-xl z-50 max-h-[90vh] overflow-y-auto">
          <div className="p-6">
            <div className="flex items-center justify-between mb-6">
              <div>
                <Dialog.Title className="text-xl font-semibold flex items-center gap-2">
                  <Share2 className="h-5 w-5" />
                  分享空间
                </Dialog.Title>
                <Dialog.Description className="text-sm text-muted-foreground mt-1">
                  分享 &quot;{space.name}&quot; 给其他人
                </Dialog.Description>
              </div>
              <Dialog.Close asChild>
                <button
                  type="button"
                  className="p-1.5 rounded-md hover:bg-muted transition-colors"
                  disabled={isLoading}
                >
                  <X className="h-5 w-5" />
                </button>
              </Dialog.Close>
            </div>

            <form onSubmit={handleCreateLink} className="space-y-4 p-4 bg-muted/50 rounded-lg mb-6">
              <h4 className="font-medium text-sm">创建新的分享链接</h4>

              <div className="grid grid-cols-2 gap-4">
                <div>
                  <Label.Root className="text-sm font-medium mb-1.5 block">
                    访问角色
                  </Label.Root>
                  <RoleSelect
                    value={linkRole}
                    onChange={setLinkRole}
                    disabled={isLoading}
                  />
                </div>
                <div>
                  <Label.Root className="text-sm font-medium mb-1.5 block">
                    有效期
                  </Label.Root>
                  <Select.Root
                    value={expiryHours?.toString() || 'null'}
                    onValueChange={(val) =>
                      setExpiryHours(val === 'null' ? null : Number(val))
                    }
                    disabled={isLoading}
                  >
                    <Select.Trigger
                      className={cn(
                        'inline-flex items-center justify-between rounded-md border bg-background px-3 py-2 text-sm h-10 w-full',
                        'focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2',
                        'disabled:opacity-50 disabled:cursor-not-allowed'
                      )}
                    >
                      <Select.Value />
                      <Select.Icon>
                        <ChevronDown className="h-4 w-4 opacity-50" />
                      </Select.Icon>
                    </Select.Trigger>
                    <Select.Portal>
                      <Select.Content className="overflow-hidden bg-background rounded-md border shadow-lg z-50 min-w-[150px]">
                        <Select.Viewport className="p-1">
                          {EXPIRY_OPTIONS.map((opt) => (
                            <Select.Item
                              key={opt.value?.toString() || 'null'}
                              value={opt.value?.toString() || 'null'}
                              className="relative flex items-center px-3 py-2 text-sm rounded-sm cursor-pointer hover:bg-muted outline-none"
                            >
                              <Select.ItemText>{opt.label}</Select.ItemText>
                            </Select.Item>
                          ))}
                        </Select.Viewport>
                      </Select.Content>
                    </Select.Portal>
                  </Select.Root>
                </div>
              </div>

              <div>
                <Label.Root className="text-sm font-medium mb-1.5 block">
                  访问密码 <span className="text-muted-foreground font-normal">(可选)</span>
                </Label.Root>
                <div className="relative">
                  <Input
                    type={showPassword ? 'text' : 'password'}
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    placeholder="设置访问密码（至少6位）"
                    className={cn(
                      'h-10 w-full bg-background border rounded-md px-3 pr-10 text-sm',
                      errors.password && 'border-destructive'
                    )}
                    disabled={isLoading}
                  />
                  <button
                    type="button"
                    onClick={() => setShowPassword(!showPassword)}
                    className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
                    disabled={isLoading}
                  >
                    {showPassword ? (
                      <EyeOff className="h-4 w-4" />
                    ) : (
                      <Eye className="h-4 w-4" />
                    )}
                  </button>
                </div>
                {errors.password && (
                  <p className="text-xs text-destructive mt-1">{errors.password}</p>
                )}
              </div>

              <div className="flex justify-end">
                <Button
                  type="submit"
                  className="h-10 px-4 bg-primary text-primary-foreground hover:bg-primary/90 rounded-md inline-flex items-center justify-center gap-2 text-sm font-medium transition-colors"
                  disabled={isLoading}
                >
                  <Link className="h-4 w-4" />
                  {isLoading ? '创建中...' : '创建链接'}
                </Button>
              </div>
            </form>

            <div>
              <h4 className="font-medium text-sm mb-4 flex items-center gap-2">
                <Link className="h-4 w-4" />
                已创建的分享链接 ({shareLinks.length})
              </h4>

              <div className="space-y-3">
                {shareLinks.map((link) => {
                  const url = getShareUrl(link.token);
                  const isExpired = link.expiresAt && new Date(link.expiresAt) < new Date();

                  return (
                    <div
                      key={link.id}
                      className={cn(
                        'p-4 rounded-lg border',
                        isExpired && 'opacity-50 bg-muted/30'
                      )}
                    >
                      <div className="flex items-start gap-3">
                        <div
                          className={cn(
                            'p-2 rounded-md',
                            isExpired ? 'bg-muted' : 'bg-primary/10 text-primary'
                          )}
                        >
                          <Link className="h-5 w-5" />
                        </div>

                        <div className="flex-1 min-w-0">
                          <div className="flex items-center gap-2 mb-1">
                            <span className="font-medium text-sm">
                              {ROLE_LABELS[link.role]} 访问
                            </span>
                            {isExpired && (
                              <span className="text-xs px-2 py-0.5 bg-destructive/10 text-destructive rounded-full">
                                已过期
                              </span>
                            )}
                          </div>
                          <div className="flex items-center gap-2 text-sm text-muted-foreground mb-2">
                            {link.expiresAt && (
                              <span className="flex items-center gap-1">
                                <Clock className="h-3.5 w-3.5" />
                                {isExpired
                                  ? '已过期'
                                  : `过期于 ${formatTimeAgo(link.expiresAt)}`}
                              </span>
                            )}
                            {link.password && (
                              <span className="flex items-center gap-1">
                                <Lock className="h-3.5 w-3.5" />
                                需要密码
                              </span>
                            )}
                          </div>
                          <div className="flex items-center gap-2">
                            <code className="flex-1 text-xs bg-muted px-2 py-1 rounded truncate">
                              {url}
                            </code>
                            <Button
                              type="button"
                              size="sm"
                              variant="ghost"
                              onClick={() => copyToClipboard(url, link.id)}
                              className="h-8 px-2 text-muted-foreground hover:text-foreground rounded-md"
                              disabled={isLoading}
                            >
                              {copiedId === link.id ? (
                                <Check className="h-4 w-4 text-green-500" />
                              ) : (
                                <Copy className="h-4 w-4" />
                              )}
                            </Button>
                            <Button
                              type="button"
                              size="sm"
                              variant="ghost"
                              onClick={() => {
                                if (confirm('确定要撤销此分享链接吗？')) {
                                  onRevokeLink(link.id);
                                }
                              }}
                              className="h-8 px-2 text-destructive hover:bg-destructive/10 hover:text-destructive rounded-md"
                              disabled={isLoading}
                            >
                              <Trash2 className="h-4 w-4" />
                            </Button>
                          </div>
                        </div>
                      </div>
                    </div>
                  );
                })}

                {shareLinks.length === 0 && (
                  <div className="p-8 text-center text-muted-foreground">
                    <Link className="h-12 w-12 mx-auto mb-3 opacity-50" />
                    <p>暂无分享链接</p>
                  </div>
                )}
              </div>
            </div>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
