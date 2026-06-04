'use client';

import { useParams, useRouter } from 'next/navigation';
import { useState } from 'react';
import {
  Settings,
  Users,
  Share2,
  Trash2,
  Save,
  Eye,
  EyeOff,
  Globe,
  Lock,
  AlertTriangle,
  Loader2,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Switch } from '@/components/ui/switch';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { trpc } from '@/components/providers/TRPCProvider';
import { useToast } from '@/components/providers/ToastProvider';
import { Skeleton } from '@/components/ui/skeleton';
import { cn } from '@/lib/utils';
import type { SpaceVisibility } from '@/lib/types/space';

const ICONS = ['📚', '📁', '💼', '🎯', '🚀', '💡', '📝', '🎨', '🔧', '📊', '🎓', '🌟'];
const COLORS = [
  'bg-blue-500',
  'bg-green-500',
  'bg-yellow-500',
  'bg-red-500',
  'bg-purple-500',
  'bg-pink-500',
  'bg-indigo-500',
  'bg-cyan-500',
];

export default function SpaceSettingsPage() {
  const params = useParams<{ spaceId: string }>();
  const router = useRouter();
  const { toast } = useToast();
  const spaceId = params?.spaceId as string;

  const { data: space, isLoading } = trpc.space.getById.useQuery({
    id: spaceId,
  });

  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [icon, setIcon] = useState('');
  const [color, setColor] = useState('bg-blue-500');
  const [visibility, setVisibility] = useState<SpaceVisibility>('PRIVATE');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [deleteConfirm, setDeleteConfirm] = useState('');
  const [showDeleteDialog, setShowDeleteDialog] = useState(false);

  const updateSpaceMutation = trpc.space.update.useMutation({
    onSuccess: () => {
      toast({
        title: '保存成功',
        description: '空间设置已更新',
        variant: 'success',
      });
    },
    onError: (error: unknown) => {
      const errorMessage = error instanceof Error ? error.message : '保存失败';
      toast({
        title: '保存失败',
        description: errorMessage,
        variant: 'destructive',
      });
    },
  });

  const setPasswordMutation = trpc.space.setPassword.useMutation({
    onSuccess: () => {
      toast({
        title: '设置成功',
        description: '访问密码已更新',
        variant: 'success',
      });
      setPassword('');
    },
    onError: (error: unknown) => {
      const errorMessage = error instanceof Error ? error.message : '设置失败';
      toast({
        title: '设置失败',
        description: errorMessage,
        variant: 'destructive',
      });
    },
  });

  const deleteSpaceMutation = trpc.space.delete.useMutation({
    onSuccess: () => {
      toast({
        title: '删除成功',
        description: '空间已删除',
        variant: 'success',
      });
      router.push('/spaces');
    },
    onError: (error: unknown) => {
      const errorMessage = error instanceof Error ? error.message : '删除失败';
      toast({
        title: '删除失败',
        description: errorMessage,
        variant: 'destructive',
      });
    },
  });

  const handleSaveBasicInfo = () => {
    updateSpaceMutation.mutate({
      id: spaceId,
      name,
      description: description || undefined,
      icon: icon || undefined,
      color,
      visibility,
    });
  };

  const handleSetPassword = () => {
    if (password.length < 6) {
      toast({
        title: '密码太短',
        description: '密码至少需要6个字符',
        variant: 'destructive',
      });
      return;
    }
    setPasswordMutation.mutate({ spaceId, password });
  };

  const handleDeleteSpace = () => {
    if (deleteConfirm !== space?.name) {
      toast({
        title: '确认失败',
        description: '请输入正确的空间名称',
        variant: 'destructive',
      });
      return;
    }
    deleteSpaceMutation.mutate({ id: spaceId });
  };

  if (isLoading) {
    return (
      <div className="p-8 max-w-4xl mx-auto">
        <Skeleton className="h-8 w-48 mb-8" />
        <div className="space-y-6">
          <Skeleton className="h-64 rounded-lg" />
          <Skeleton className="h-64 rounded-lg" />
          <Skeleton className="h-64 rounded-lg" />
        </div>
      </div>
    );
  }

  if (!space) {
    return (
      <div className="p-8">
        <Alert variant="destructive">
          <AlertTriangle className="h-4 w-4" />
          <AlertTitle>空间不存在</AlertTitle>
          <AlertDescription>您访问的空间不存在或已被删除。</AlertDescription>
        </Alert>
      </div>
    );
  }

  if (!name && space) {
    setName(space.name);
    setDescription(space.description || '');
    setIcon(space.icon || '');
    setColor(space.color || 'bg-blue-500');
    setVisibility(space.visibility as SpaceVisibility);
  }

  return (
    <div className="p-8 max-w-4xl mx-auto">
      <div className="flex items-center gap-3 mb-8">
        <div className="p-2 rounded-lg bg-primary/10">
          <Settings className="h-6 w-6 text-primary" />
        </div>
        <div>
          <h1 className="text-2xl font-bold">空间设置</h1>
          <p className="text-muted-foreground">管理您的空间配置</p>
        </div>
      </div>

      <Tabs defaultValue="basic">
        <TabsList className="mb-6">
          <TabsTrigger value="basic">基本信息</TabsTrigger>
          <TabsTrigger value="sharing">分享设置</TabsTrigger>
          <TabsTrigger value="danger">危险操作</TabsTrigger>
        </TabsList>

        <TabsContent value="basic">
          <Card>
            <CardHeader>
              <CardTitle>基本信息</CardTitle>
            </CardHeader>
            <CardContent className="space-y-6">
              <div className="flex items-start gap-6">
                <div className="space-y-4">
                  <div
                    className={cn(
                      'w-24 h-24 rounded-xl flex items-center justify-center text-white text-3xl font-bold',
                      color
                    )}
                  >
                    {icon || name.charAt(0).toUpperCase() || '?'}
                  </div>
                  <div>
                    <Label className="text-sm font-medium mb-2 block">
                      图标
                    </Label>
                    <div className="flex flex-wrap gap-2 max-w-[200px]">
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
                        >
                          {i}
                        </button>
                      ))}
                    </div>
                  </div>
                  <div>
                    <Label className="text-sm font-medium mb-2 block">
                      颜色
                    </Label>
                    <div className="flex flex-wrap gap-2">
                      {COLORS.map((c) => (
                        <button
                          key={c}
                          type="button"
                          onClick={() => setColor(c)}
                          className={cn(
                            'h-8 w-8 rounded-full transition-all',
                            c,
                            color === c && 'ring-2 ring-offset-2 ring-primary scale-110'
                          )}
                        />
                      ))}
                    </div>
                  </div>
                </div>

                <div className="flex-1 space-y-4">
                  <div>
                    <Label htmlFor="name">空间名称</Label>
                    <Input
                      id="name"
                      value={name}
                      onChange={(e) => setName(e.target.value)}
                      className="mt-1.5"
                      maxLength={100}
                    />
                    <p className="text-xs text-muted-foreground mt-1 text-right">
                      {name.length}/100
                    </p>
                  </div>

                  <div>
                    <Label htmlFor="description">描述</Label>
                    <Textarea
                      id="description"
                      value={description}
                      onChange={(e) => setDescription(e.target.value)}
                      className="mt-1.5"
                      rows={4}
                      maxLength={500}
                      placeholder="描述一下这个空间的用途..."
                    />
                    <p className="text-xs text-muted-foreground mt-1 text-right">
                      {description.length}/500
                    </p>
                  </div>

                  <div>
                    <Label className="text-sm font-medium mb-2 block">
                      可见性
                    </Label>
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
                </div>
              </div>

              <div className="flex justify-end pt-4 border-t">
                <Button
                  onClick={handleSaveBasicInfo}
                  disabled={updateSpaceMutation.isLoading}
                >
                  {updateSpaceMutation.isLoading && (
                    <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                  )}
                  <Save className="mr-2 h-4 w-4" />
                  保存更改
                </Button>
              </div>
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="sharing">
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                <Share2 className="h-5 w-5" />
                分享设置
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-6">
              <div>
                <Label className="text-sm font-medium mb-3 block">
                  访问密码
                </Label>
                <p className="text-sm text-muted-foreground mb-4">
                  设置访问密码后，访问公开空间需要输入密码才能查看内容。
                </p>
                <div className="flex gap-2">
                  <div className="relative flex-1">
                    <Input
                      type={showPassword ? 'text' : 'password'}
                      value={password}
                      onChange={(e) => setPassword(e.target.value)}
                      placeholder="设置访问密码（至少6位）"
                      className="pr-10"
                    />
                    <button
                      type="button"
                      onClick={() => setShowPassword(!showPassword)}
                      className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
                    >
                      {showPassword ? (
                        <EyeOff className="h-4 w-4" />
                      ) : (
                        <Eye className="h-4 w-4" />
                      )}
                    </button>
                  </div>
                  <Button
                    onClick={handleSetPassword}
                    disabled={!password || setPasswordMutation.isLoading}
                  >
                    {setPasswordMutation.isLoading && (
                      <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                    )}
                    设置密码
                  </Button>
                </div>
              </div>

              <div className="pt-4 border-t">
                <div className="flex items-center justify-between">
                  <div>
                    <p className="font-medium">允许邀请成员</p>
                    <p className="text-sm text-muted-foreground">
                      允许管理员和编辑者邀请新成员加入空间
                    </p>
                  </div>
                  <Switch defaultChecked />
                </div>
              </div>

              <div className="pt-4 border-t">
                <Label className="text-sm font-medium mb-3 block">
                  默认角色
                </Label>
                <p className="text-sm text-muted-foreground mb-3">
                  新成员加入空间时的默认角色
                </p>
                <Select defaultValue="VIEWER">
                  <SelectTrigger className="w-48">
                    <SelectValue placeholder="选择角色" />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="VIEWER">查看者</SelectItem>
                    <SelectItem value="EDITOR">编辑者</SelectItem>
                    <SelectItem value="ADMIN">管理员</SelectItem>
                  </SelectContent>
                </Select>
              </div>
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="danger">
          <Card className="border-destructive">
            <CardHeader>
              <CardTitle className="text-destructive flex items-center gap-2">
                <AlertTriangle className="h-5 w-5" />
                危险操作
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              <Alert variant="destructive">
                <AlertTriangle className="h-4 w-4" />
                <AlertTitle>警告</AlertTitle>
                <AlertDescription>
                  删除空间将永久删除所有相关文档、评论和数据。此操作不可撤销。
                </AlertDescription>
              </Alert>

              <div className="space-y-4">
                <div>
                  <Label htmlFor="delete-confirm">
                    请输入空间名称 <span className="font-semibold">{space.name}</span> 以确认删除
                  </Label>
                  <Input
                    id="delete-confirm"
                    value={deleteConfirm}
                    onChange={(e) => setDeleteConfirm(e.target.value)}
                    placeholder="输入空间名称确认删除"
                    className="mt-1.5"
                  />
                </div>

                <Dialog open={showDeleteDialog} onOpenChange={setShowDeleteDialog}>
                  <DialogTrigger asChild>
                    <Button
                      variant="destructive"
                      disabled={deleteConfirm !== space.name}
                    >
                      <Trash2 className="mr-2 h-4 w-4" />
                      删除空间
                    </Button>
                  </DialogTrigger>
                  <DialogContent>
                    <DialogHeader>
                      <DialogTitle>确认删除空间</DialogTitle>
                      <DialogDescription>
                        您确定要删除空间 "{space.name}" 吗？此操作将永久删除：
                        <ul className="list-disc list-inside mt-2 space-y-1">
                          <li>所有文档及其版本历史</li>
                          <li>所有评论和讨论</li>
                          <li>所有标签和分类</li>
                          <li>所有成员权限配置</li>
                        </ul>
                        <p className="mt-4 text-destructive font-medium">
                          此操作不可撤销！
                        </p>
                      </DialogDescription>
                    </DialogHeader>
                    <DialogFooter>
                      <Button
                        variant="outline"
                        onClick={() => setShowDeleteDialog(false)}
                      >
                        取消
                      </Button>
                      <Button
                        variant="destructive"
                        onClick={handleDeleteSpace}
                        disabled={deleteSpaceMutation.isLoading}
                      >
                        {deleteSpaceMutation.isLoading && (
                          <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                        )}
                        确认删除
                      </Button>
                    </DialogFooter>
                  </DialogContent>
                </Dialog>
              </div>
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>
    </div>
  );
}
