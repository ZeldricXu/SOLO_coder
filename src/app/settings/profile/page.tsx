'use client';

import { useRouter } from 'next/navigation';
import { useState } from 'react';
import {
  User,
  Mail,
  Calendar,
  Shield,
  Save,
  Upload,
  Loader2,
  Crown,
  Settings,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Badge } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { trpc } from '@/components/providers/TRPCProvider';
import { useToast } from '@/components/providers/ToastProvider';
import { formatTimeAgo } from '@/lib/utils';

export default function ProfilePage() {
  const router = useRouter();
  const { toast } = useToast();

  const { data: user, isLoading } = trpc.auth.getCurrentUser.useQuery();

  const [name, setName] = useState(user?.name || '');
  const [bio, setBio] = useState('');
  const [isSaving, setIsSaving] = useState(false);

  const handleSave = () => {
    setIsSaving(true);
    setTimeout(() => {
      setIsSaving(false);
      toast({
        title: '保存成功',
        description: '个人资料已更新',
        variant: 'success',
      });
    }, 1000);
  };

  const handleAvatarUpload = () => {
    toast({
      title: '功能开发中',
      description: '头像上传功能即将上线',
    });
  };

  if (isLoading) {
    return (
      <div className="p-8 max-w-3xl mx-auto">
        <div className="flex items-center gap-3 mb-8">
          <div className="p-2 rounded-lg bg-primary/10">
            <Skeleton className="h-6 w-6" />
          </div>
          <div>
            <Skeleton className="h-8 w-32 mb-2" />
            <Skeleton className="h-4 w-48" />
          </div>
        </div>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
          <div className="md:col-span-1">
            <Skeleton className="h-64 rounded-lg" />
          </div>
          <div className="md:col-span-2 space-y-6">
            <Skeleton className="h-64 rounded-lg" />
            <Skeleton className="h-48 rounded-lg" />
          </div>
        </div>
      </div>
    );
  }

  if (!user) {
    return (
      <div className="p-8">
        <Alert variant="destructive">
          <AlertTitle>未登录</AlertTitle>
          <AlertDescription>
            请先登录后再查看个人资料。
          </AlertDescription>
        </Alert>
        <Button className="mt-4" onClick={() => router.push('/login')}>
          去登录
        </Button>
      </div>
    );
  }

  if (!name && user) {
    setName(user.name);
  }

  return (
    <div className="min-h-screen bg-background">
      <header className="border-b">
        <div className="container mx-auto px-4 py-6">
          <div className="flex items-center gap-3">
            <div className="p-2 rounded-lg bg-primary/10">
              <User className="h-6 w-6 text-primary" />
            </div>
            <div>
              <h1 className="text-3xl font-bold">个人资料</h1>
              <p className="text-muted-foreground mt-1">
                管理您的个人信息和偏好设置
              </p>
            </div>
          </div>
        </div>
      </header>

      <main className="container mx-auto px-4 py-8">
        <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
          <div className="md:col-span-1">
            <Card>
              <CardContent className="p-6 text-center">
                <div className="relative inline-block">
                  <Avatar className="h-32 w-32 mx-auto">
                    <AvatarImage src={user.avatar || ''} alt={user.name} />
                    <AvatarFallback className="text-4xl">
                      {user.name.charAt(0).toUpperCase()}
                    </AvatarFallback>
                  </Avatar>
                  <button
                    onClick={handleAvatarUpload}
                    className="absolute bottom-0 right-0 p-2 rounded-full bg-primary text-primary-foreground shadow-lg hover:bg-primary/90 transition-colors"
                  >
                    <Upload className="h-4 w-4" />
                  </button>
                </div>

                <h2 className="text-xl font-semibold mt-4">{user.name}</h2>
                <p className="text-sm text-muted-foreground">{user.email}</p>

                <div className="flex items-center justify-center gap-2 mt-3">
                  <Badge
                    variant={user.role === 'OWNER' ? 'default' : 'outline'}
                  >
                    {user.role === 'OWNER' ? (
                      <Crown className="h-3 w-3 mr-1" />
                    ) : (
                      <Shield className="h-3 w-3 mr-1" />
                    )}
                    {user.role === 'OWNER'
                      ? '所有者'
                      : user.role === 'ADMIN'
                      ? '管理员'
                      : user.role === 'EDITOR'
                      ? '编辑者'
                      : '查看者'}
                  </Badge>
                </div>

                <div className="mt-6 pt-6 border-t text-left space-y-3 text-sm">
                  <div className="flex items-center gap-2 text-muted-foreground">
                    <Mail className="h-4 w-4" />
                    <span>{user.email}</span>
                  </div>
                  <div className="flex items-center gap-2 text-muted-foreground">
                    <Calendar className="h-4 w-4" />
                    <span>加入于 {formatTimeAgo(user.createdAt)}</span>
                  </div>
                </div>

                <div className="mt-6 pt-6 border-t">
                  <Button
                    variant="outline"
                    className="w-full"
                    onClick={() => router.push('/settings/preferences')}
                  >
                    <Settings className="mr-2 h-4 w-4" />
                    偏好设置
                  </Button>
                </div>
              </CardContent>
            </Card>
          </div>

          <div className="md:col-span-2 space-y-6">
            <Card>
              <CardHeader>
                <CardTitle className="text-lg">基本信息</CardTitle>
              </CardHeader>
              <CardContent className="space-y-4">
                <div>
                  <Label htmlFor="name">用户名</Label>
                  <Input
                    id="name"
                    value={name}
                    onChange={(e) => setName(e.target.value)}
                    className="mt-1.5"
                    maxLength={50}
                  />
                  <p className="text-xs text-muted-foreground mt-1 text-right">
                    {name.length}/50
                  </p>
                </div>

                <div>
                  <Label htmlFor="email">邮箱地址</Label>
                  <Input
                    id="email"
                    type="email"
                    value={user.email}
                    disabled
                    className="mt-1.5"
                  />
                  <p className="text-xs text-muted-foreground mt-1">
                    邮箱地址不可修改
                  </p>
                </div>

                <div>
                  <Label htmlFor="bio">个人简介</Label>
                  <Textarea
                    id="bio"
                    value={bio}
                    onChange={(e) => setBio(e.target.value)}
                    placeholder="介绍一下自己..."
                    className="mt-1.5"
                    rows={4}
                    maxLength={200}
                  />
                  <p className="text-xs text-muted-foreground mt-1 text-right">
                    {bio.length}/200
                  </p>
                </div>

                <div className="flex justify-end pt-4">
                  <Button onClick={handleSave} disabled={isSaving}>
                    {isSaving && (
                      <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                    )}
                    <Save className="mr-2 h-4 w-4" />
                    保存更改
                  </Button>
                </div>
              </CardContent>
            </Card>

            <Card>
              <CardHeader>
                <CardTitle className="text-lg">账户安全</CardTitle>
              </CardHeader>
              <CardContent className="space-y-4">
                <div className="flex items-center justify-between p-4 rounded-lg bg-muted/50">
                  <div>
                    <p className="font-medium">修改密码</p>
                    <p className="text-sm text-muted-foreground">
                      定期修改密码可以提高账户安全性
                    </p>
                  </div>
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => {
                      toast({
                        title: '功能开发中',
                        description: '密码修改功能即将上线',
                      });
                    }}
                  >
                    修改
                  </Button>
                </div>

                <div className="flex items-center justify-between p-4 rounded-lg bg-muted/50">
                  <div>
                    <p className="font-medium">两步验证</p>
                    <p className="text-sm text-muted-foreground">
                      启用两步验证以增强账户安全性
                    </p>
                  </div>
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => {
                      toast({
                        title: '功能开发中',
                        description: '两步验证功能即将上线',
                      });
                    }}
                  >
                    启用
                  </Button>
                </div>

                <div className="flex items-center justify-between p-4 rounded-lg bg-muted/50">
                  <div>
                    <p className="font-medium">登录设备</p>
                    <p className="text-sm text-muted-foreground">
                      管理您的登录设备和会话
                    </p>
                  </div>
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => {
                      toast({
                        title: '功能开发中',
                        description: '设备管理功能即将上线',
                      });
                    }}
                  >
                    管理
                  </Button>
                </div>
              </CardContent>
            </Card>
          </div>
        </div>
      </main>
    </div>
  );
}
