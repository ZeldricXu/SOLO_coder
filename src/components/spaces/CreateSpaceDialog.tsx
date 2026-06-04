'use client';

import { useState } from 'react';
import * as Dialog from '@radix-ui/react-dialog';
import * as Label from '@radix-ui/react-label';
import { X, Plus, Eye, EyeOff, Globe, Lock } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@radix-ui/react-input';
import { cn } from '@/lib/utils';
import type { SpaceVisibility, CreateSpaceInput } from '@/lib/types/space';

interface CreateSpaceDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onCreate: (input: CreateSpaceInput) => void;
  isLoading?: boolean;
}

const ICONS = ['📚', '📁', '💼', '🎯', '🚀', '💡', '📝', '🎨', '🔧', '📊', '🎓', '🌟'];

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

export function CreateSpaceDialog({
  open,
  onOpenChange,
  onCreate,
  isLoading = false,
}: CreateSpaceDialogProps) {
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [icon, setIcon] = useState('');
  const [color, setColor] = useState('bg-blue-500');
  const [visibility, setVisibility] = useState<SpaceVisibility>('PRIVATE');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({});

  const resetForm = () => {
    setName('');
    setDescription('');
    setIcon('');
    setColor('bg-blue-500');
    setVisibility('PRIVATE');
    setPassword('');
    setShowPassword(false);
    setErrors({});
  };

  const handleOpenChange = (newOpen: boolean) => {
    if (!newOpen) {
      resetForm();
    }
    onOpenChange(newOpen);
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

    if (visibility === 'PUBLIC' && password && password.length < 6) {
      newErrors.password = '密码至少6个字符';
    }

    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();

    if (!validate()) return;

    onCreate({
      name: name.trim(),
      description: description.trim() || undefined,
      icon: icon || undefined,
      color,
      visibility,
      password: password || undefined,
    });

    resetForm();
    onOpenChange(false);
  };

  return (
    <Dialog.Root open={open} onOpenChange={handleOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 bg-black/50 backdrop-blur-sm z-50" />
        <Dialog.Content className="fixed left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2 w-full max-w-lg bg-background rounded-xl shadow-xl z-50 max-h-[90vh] overflow-y-auto">
          <form onSubmit={handleSubmit} className="p-6 space-y-6">
            <div className="flex items-center justify-between">
              <Dialog.Title className="text-xl font-semibold">
                创建新空间
              </Dialog.Title>
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

            <div className="space-y-4">
              <div className="flex items-center gap-4">
                <div
                  className={cn(
                    'flex h-16 w-16 items-center justify-center rounded-xl text-white text-2xl font-bold',
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
                    onChange={(e) => setName(e.target.value)}
                    placeholder="输入空间名称"
                    className={cn(
                      'h-10 w-full bg-background border rounded-md px-3 text-sm',
                      errors.name && 'border-destructive'
                    )}
                    disabled={isLoading}
                  />
                  {errors.name && (
                    <p className="text-xs text-destructive mt-1">{errors.name}</p>
                  )}
                </div>
              </div>

              <div>
                <Label.Root className="text-sm font-medium mb-1.5 block">
                  图标
                </Label.Root>
                <div className="flex flex-wrap gap-2">
                  {ICONS.map((i) => (
                    <button
                      key={i}
                      type="button"
                      onClick={() => setIcon(icon === i ? '' : i)}
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
                      onClick={() => setColor(c.value)}
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

              <div>
                <Label.Root className="text-sm font-medium mb-1.5 block">
                  描述
                </Label.Root>
                <textarea
                  value={description}
                  onChange={(e) => setDescription(e.target.value)}
                  placeholder="描述一下这个空间的用途..."
                  rows={3}
                  className={cn(
                    'w-full bg-background border rounded-md px-3 py-2 text-sm resize-none',
                    errors.description && 'border-destructive'
                  )}
                  disabled={isLoading}
                />
                {errors.description && (
                  <p className="text-xs text-destructive mt-1">{errors.description}</p>
                )}
                <p className="text-xs text-muted-foreground mt-1 text-right">
                  {description.length}/500
                </p>
              </div>

              <div>
                <Label.Root className="text-sm font-medium mb-1.5 block">
                  可见性
                </Label.Root>
                <div className="grid grid-cols-2 gap-3">
                  <button
                    type="button"
                    onClick={() => setVisibility('PRIVATE')}
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
                    onClick={() => setVisibility('PUBLIC')}
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

              {visibility === 'PUBLIC' && (
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
              )}
            </div>

            <div className="flex justify-end gap-3 pt-4 border-t">
              <Dialog.Close asChild>
                <Button
                  type="button"
                  variant="secondary"
                  className="h-10 px-4 bg-secondary text-secondary-foreground hover:bg-secondary/80 rounded-md inline-flex items-center justify-center text-sm font-medium transition-colors"
                  disabled={isLoading}
                >
                  取消
                </Button>
              </Dialog.Close>
              <Button
                type="submit"
                className="h-10 px-4 bg-primary text-primary-foreground hover:bg-primary/90 rounded-md inline-flex items-center justify-center gap-2 text-sm font-medium transition-colors"
                disabled={isLoading}
              >
                <Plus className="h-4 w-4" />
                {isLoading ? '创建中...' : '创建空间'}
              </Button>
            </div>
          </form>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
