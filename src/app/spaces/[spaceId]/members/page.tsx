'use client';

import { useParams } from 'next/navigation';
import { useState } from 'react';
import {
  Users,
  UserPlus,
  MoreHorizontal,
  Trash2,
  Shield,
  Loader2,
  Search,
  X,
  Mail,
  Crown,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog';
import { Badge } from '@/components/ui/badge';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { trpc } from '@/components/providers/TRPCProvider';
import { useToast } from '@/components/providers/ToastProvider';
import { Skeleton } from '@/components/ui/skeleton';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import type { Role } from '@/lib/types/permission';
import type { SpaceMember } from '@/lib/types/space';

const ROLE_LABELS: Record<Role, string> = {
  OWNER: '所有者',
  ADMIN: '管理员',
  EDITOR: '编辑者',
  VIEWER: '查看者',
};

const ROLE_DESCRIPTIONS: Record<Role, string> = {
  OWNER: '完全控制，可删除空间',
  ADMIN: '管理成员、设置和内容',
  EDITOR: '创建和编辑文档',
  VIEWER: '查看和评论文档',
};

export default function SpaceMembersPage() {
  const params = useParams<{ spaceId: string }>();
  const { toast } = useToast();
  const spaceId = params?.spaceId as string;

  const [searchQuery, setSearchQuery] = useState('');
  const [showAddDialog, setShowAddDialog] = useState(false);
  const [newMemberEmail, setNewMemberEmail] = useState('');
  const [newMemberRole, setNewMemberRole] = useState<Role>('VIEWER');
  const [errors, setErrors] = useState<{ email?: string }>({});

  const { data: members, isLoading, refetch } =
    trpc.space.listMembers.useQuery(
      { spaceId },
      { enabled: !!spaceId }
    );

  const { data: userRole } = trpc.space.getUserRole.useQuery(
    { spaceId },
    { enabled: !!spaceId }
  );

  const addMemberMutation = trpc.space.addMember.useMutation({
    onSuccess: () => {
      toast({
        title: '添加成功',
        description: '成员已添加到空间',
        variant: 'success',
      });
      setShowAddDialog(false);
      setNewMemberEmail('');
      setNewMemberRole('VIEWER');
      setErrors({});
      refetch();
    },
    onError: (error: unknown) => {
      const errorMessage = error instanceof Error ? error.message : '添加失败';
      toast({
        title: '添加失败',
        description: errorMessage,
        variant: 'destructive',
      });
    },
  });

  const updateRoleMutation = trpc.space.updateMemberRole.useMutation({
    onSuccess: () => {
      toast({
        title: '更新成功',
        description: '成员角色已更新',
        variant: 'success',
      });
      refetch();
    },
    onError: (error: unknown) => {
      const errorMessage = error instanceof Error ? error.message : '更新失败';
      toast({
        title: '更新失败',
        description: errorMessage,
        variant: 'destructive',
      });
    },
  });

  const removeMemberMutation = trpc.space.removeMember.useMutation({
    onSuccess: () => {
      toast({
        title: '移除成功',
        description: '成员已从空间移除',
        variant: 'success',
      });
      refetch();
    },
    onError: (error: unknown) => {
      const errorMessage = error instanceof Error ? error.message : '移除失败';
      toast({
        title: '移除失败',
        description: errorMessage,
        variant: 'destructive',
      });
    },
  });

  const validateEmail = () => {
    const newErrors: { email?: string } = {};
    if (!newMemberEmail) {
      newErrors.email = '请输入邮箱地址';
    } else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(newMemberEmail)) {
      newErrors.email = '邮箱格式不正确';
    }
    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  const handleAddMember = () => {
    if (!validateEmail()) return;
    addMemberMutation.mutate({
      spaceId,
      email: newMemberEmail,
      role: newMemberRole,
    });
  };

  const handleUpdateRole = (userId: string, role: Role) => {
    updateRoleMutation.mutate({ spaceId, userId, role });
  };

  const handleRemoveMember = (userId: string) => {
    if (confirm('确定要移除该成员吗？')) {
      removeMemberMutation.mutate({ spaceId, userId });
    }
  };

  const canManageMembers =
    userRole?.role === 'OWNER' || userRole?.role === 'ADMIN';

  const filteredMembers = members?.filter((m: SpaceMember) =>
    m.user.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
    m.user.email.toLowerCase().includes(searchQuery.toLowerCase())
  );

  if (isLoading) {
    return (
      <div className="p-8 max-w-4xl mx-auto">
        <Skeleton className="h-8 w-48 mb-8" />
        <Card>
          <CardContent className="p-6">
            <div className="space-y-4">
              {[1, 2, 3, 4, 5].map((i) => (
                <Skeleton key={i} className="h-16 w-full rounded-lg" />
              ))}
            </div>
          </CardContent>
        </Card>
      </div>
    );
  }

  return (
    <div className="p-8 max-w-4xl mx-auto">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 mb-8">
        <div className="flex items-center gap-3">
          <div className="p-2 rounded-lg bg-primary/10">
            <Users className="h-6 w-6 text-primary" />
          </div>
          <div>
            <h1 className="text-2xl font-bold">成员管理</h1>
            <p className="text-muted-foreground">
              管理空间成员和权限设置
            </p>
          </div>
        </div>

        {canManageMembers && (
          <Dialog open={showAddDialog} onOpenChange={setShowAddDialog}>
            <DialogTrigger asChild>
              <Button>
                <UserPlus className="mr-2 h-4 w-4" />
                添加成员
              </Button>
            </DialogTrigger>
            <DialogContent>
              <DialogHeader>
                <DialogTitle>添加成员</DialogTitle>
                <DialogDescription>
                  通过邮箱邀请新成员加入此空间
                </DialogDescription>
              </DialogHeader>
              <div className="space-y-4 py-4">
                <div className="space-y-2">
                  <Label htmlFor="email">邮箱地址</Label>
                  <div className="relative">
                    <Mail className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
                    <Input
                      id="email"
                      type="email"
                      placeholder="member@example.com"
                      className={`pl-10 ${errors.email ? 'border-destructive' : ''}`}
                      value={newMemberEmail}
                      onChange={(e) => {
                        setNewMemberEmail(e.target.value);
                        if (errors.email) setErrors({});
                      }}
                    />
                  </div>
                  {errors.email && (
                    <p className="text-sm text-destructive">{errors.email}</p>
                  )}
                </div>
                <div className="space-y-2">
                  <Label htmlFor="role">角色</Label>
                  <Select
                    value={newMemberRole}
                    onValueChange={(value) => setNewMemberRole(value as Role)}
                  >
                    <SelectTrigger>
                      <SelectValue placeholder="选择角色" />
                    </SelectTrigger>
                    <SelectContent>
                      {(Object.keys(ROLE_LABELS) as Role[])
                        .filter((r) => r !== 'OWNER')
                        .map((role) => (
                          <SelectItem key={role} value={role}>
                            <div className="flex items-center gap-2">
                              <span>{ROLE_LABELS[role]}</span>
                              <span className="text-xs text-muted-foreground">
                                - {ROLE_DESCRIPTIONS[role]}
                              </span>
                            </div>
                          </SelectItem>
                        ))}
                    </SelectContent>
                  </Select>
                </div>
              </div>
              <DialogFooter>
                <Button
                  variant="outline"
                  onClick={() => setShowAddDialog(false)}
                >
                  取消
                </Button>
                <Button
                  onClick={handleAddMember}
                  disabled={addMemberMutation.isLoading}
                >
                  {addMemberMutation.isLoading && (
                    <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                  )}
                  添加
                </Button>
              </DialogFooter>
            </DialogContent>
          </Dialog>
        )}
      </div>

      {!canManageMembers && (
        <Alert className="mb-6">
          <Shield className="h-4 w-4" />
          <AlertTitle>权限不足</AlertTitle>
          <AlertDescription>
            您没有权限管理此空间的成员。如需添加或移除成员，请联系空间所有者或管理员。
          </AlertDescription>
        </Alert>
      )}

      <Card>
        <CardHeader className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
          <CardTitle>
            成员列表
            <Badge variant="secondary" className="ml-2">
              {members?.length || 0}
            </Badge>
          </CardTitle>
          <div className="relative">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
            <Input
              placeholder="搜索成员..."
              className="pl-10 w-64"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
            />
            {searchQuery && (
              <button
                onClick={() => setSearchQuery('')}
                className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
              >
                <X className="h-4 w-4" />
              </button>
            )}
          </div>
        </CardHeader>
        <CardContent>
          <div className="space-y-3">
            {filteredMembers?.length === 0 ? (
              <div className="text-center py-8 text-muted-foreground">
                <Users className="h-12 w-12 mx-auto mb-3 opacity-50" />
                <p>{searchQuery ? '没有找到匹配的成员' : '暂无成员'}</p>
              </div>
            ) : (
              filteredMembers?.map((member: SpaceMember) => (
                <div
                  key={member.user.id}
                  className="flex items-center gap-4 p-3 rounded-lg hover:bg-muted transition-colors"
                >
                  <Avatar className="h-10 w-10">
                    <AvatarImage
                      src={member.user.avatar || ''}
                      alt={member.user.name}
                    />
                    <AvatarFallback>
                      {member.user.name.charAt(0).toUpperCase()}
                    </AvatarFallback>
                  </Avatar>
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2">
                      <p className="font-medium truncate">
                        {member.user.name}
                      </p>
                      {member.role === 'OWNER' && (
                        <Badge className="bg-yellow-100 text-yellow-800 dark:bg-yellow-900/30 dark:text-yellow-300">
                          <Crown className="h-3 w-3 mr-1" />
                          所有者
                        </Badge>
                      )}
                    </div>
                    <p className="text-sm text-muted-foreground truncate">
                      {member.user.email}
                    </p>
                  </div>
                  <div className="flex items-center gap-2">
                    {canManageMembers &&
                    member.role !== 'OWNER' &&
                    userRole?.role === 'OWNER' ? (
                      <Select
                        value={member.role}
                        onValueChange={(value) =>
                          handleUpdateRole(member.user.id, value as Role)
                        }
                        disabled={updateRoleMutation.isLoading}
                      >
                        <SelectTrigger className="w-32 h-8">
                          <SelectValue />
                        </SelectTrigger>
                        <SelectContent>
                          {(Object.keys(ROLE_LABELS) as Role[])
                            .filter((r) => r !== 'OWNER')
                            .map((role) => (
                              <SelectItem key={role} value={role}>
                                {ROLE_LABELS[role]}
                              </SelectItem>
                            ))}
                        </SelectContent>
                      </Select>
                    ) : (
                      <Badge variant="outline">
                        {ROLE_LABELS[member.role as Role]}
                      </Badge>
                    )}

                    {canManageMembers &&
                      member.role !== 'OWNER' &&
                      (userRole?.role === 'OWNER' ||
                        (userRole?.role === 'ADMIN' &&
                          member.role !== 'ADMIN')) && (
                        <DropdownMenu>
                          <DropdownMenuTrigger asChild>
                            <Button variant="ghost" size="icon" className="h-8 w-8">
                              <MoreHorizontal className="h-4 w-4" />
                            </Button>
                          </DropdownMenuTrigger>
                          <DropdownMenuContent align="end">
                            <DropdownMenuItem
                              className="text-destructive focus:text-destructive"
                              onClick={() => handleRemoveMember(member.user.id)}
                              disabled={removeMemberMutation.isLoading}
                            >
                              <Trash2 className="mr-2 h-4 w-4" />
                              移除成员
                            </DropdownMenuItem>
                          </DropdownMenuContent>
                        </DropdownMenu>
                      )}
                  </div>
                </div>
              ))
            )}
          </div>
        </CardContent>
      </Card>

      <Card className="mt-6">
        <CardHeader>
          <CardTitle>角色权限说明</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            {(Object.keys(ROLE_LABELS) as Role[]).map((role) => (
              <div
                key={role}
                className="p-4 rounded-lg border bg-muted/30"
              >
                <div className="flex items-center gap-2 mb-2">
                  {role === 'OWNER' && <Crown className="h-4 w-4 text-yellow-500" />}
                  {role === 'ADMIN' && <Shield className="h-4 w-4 text-blue-500" />}
                  <span className="font-medium">{ROLE_LABELS[role]}</span>
                </div>
                <p className="text-sm text-muted-foreground">
                  {ROLE_DESCRIPTIONS[role]}
                </p>
              </div>
            ))}
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
