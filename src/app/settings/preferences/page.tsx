'use client';

import {
  Settings,
  Moon,
  Sun,
  Languages,
  Eye,
  Save,
  Loader2,
  Bell,
  FileText,
  Mail,
} from 'lucide-react';
import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Label } from '@/components/ui/label';
import { Switch } from '@/components/ui/switch';
import { Separator } from '@/components/ui/separator';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { trpc } from '@/components/providers/TRPCProvider';
import { useToast } from '@/components/providers/ToastProvider';
import { useTheme } from '@/components/providers/ThemeProvider';

export default function PreferencesPage() {
  const { toast } = useToast();
  const { theme, setTheme } = useTheme();

  const { data: user } = trpc.auth.getCurrentUser.useQuery();

  const [language, setLanguage] = useState('zh-CN');
  const [isSaving, setIsSaving] = useState(false);

  const [notifications, setNotifications] = useState({
    email: true,
    browser: true,
    comment: true,
    mention: true,
    review: true,
    sync: false,
  });

  const [editor, setEditor] = useState({
    autoSave: true,
    spellCheck: true,
    lineNumbers: false,
    wordWrap: true,
    defaultView: 'edit' as 'edit' | 'preview' | 'split',
    fontSize: 14,
  });

  const [display, setDisplay] = useState({
    density: 'comfortable' as 'compact' | 'comfortable' | 'spacious',
    sidebarWidth: 280,
    showLineNumbers: true,
    enableAnimation: true,
  });

  const handleSave = () => {
    setIsSaving(true);
    setTimeout(() => {
      setIsSaving(false);
      toast({
        title: '保存成功',
        description: '偏好设置已更新',
        variant: 'success',
      });
    }, 1000);
  };

  return (
    <div className="min-h-screen bg-background">
      <header className="border-b">
        <div className="container mx-auto px-4 py-6">
          <div className="flex items-center gap-3">
            <div className="p-2 rounded-lg bg-primary/10">
              <Settings className="h-6 w-6 text-primary" />
            </div>
            <div>
              <h1 className="text-3xl font-bold">偏好设置</h1>
              <p className="text-muted-foreground mt-1">
                自定义您的使用体验
              </p>
            </div>
          </div>
        </div>
      </header>

      <main className="container mx-auto px-4 py-8 max-w-3xl">
        <div className="space-y-6">
          <Card>
            <CardHeader>
              <CardTitle className="text-lg flex items-center gap-2">
                <Eye className="h-5 w-5" />
                外观设置
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-6">
              <div className="flex items-center justify-between">
                <div>
                  <Label className="font-medium">主题模式</Label>
                  <p className="text-sm text-muted-foreground">
                    选择您喜欢的显示模式
                  </p>
                </div>
                <div className="flex items-center gap-2">
                  <Button
                    variant={theme === 'light' ? 'default' : 'outline'}
                    size="icon"
                    onClick={() => setTheme('light')}
                  >
                    <Sun className="h-4 w-4" />
                  </Button>
                  <Button
                    variant={theme === 'dark' ? 'default' : 'outline'}
                    size="icon"
                    onClick={() => setTheme('dark')}
                  >
                    <Moon className="h-4 w-4" />
                  </Button>
                </div>
              </div>

              <Separator className="my-4" />

              <div className="flex items-center justify-between">
                <div>
                  <Label className="font-medium">语言</Label>
                  <p className="text-sm text-muted-foreground">
                    选择界面显示语言
                  </p>
                </div>
                <Select
                  value={language}
                  onValueChange={setLanguage}
                >
                  <SelectTrigger className="w-40">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="zh-CN">简体中文</SelectItem>
                    <SelectItem value="zh-TW">繁體中文</SelectItem>
                    <SelectItem value="en-US">English</SelectItem>
                    <SelectItem value="ja-JP">日本語</SelectItem>
                  </SelectContent>
                </Select>
              </div>

              <Separator className="my-4" />

              <div className="flex items-center justify-between">
                <div>
                  <Label className="font-medium">界面密度</Label>
                  <p className="text-sm text-muted-foreground">
                    调整界面元素的间距
                  </p>
                </div>
                <Select
                  value={display.density}
                  onValueChange={(v) =>
                    setDisplay({ ...display, density: v as typeof display.density })
                  }
                >
                  <SelectTrigger className="w-32">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="compact">紧凑</SelectItem>
                    <SelectItem value="comfortable">舒适</SelectItem>
                    <SelectItem value="spacious">宽松</SelectItem>
                  </SelectContent>
                </Select>
              </div>

              <Separator className="my-4" />

              <div className="flex items-center justify-between">
                <div>
                  <Label className="font-medium">启用动画</Label>
                  <p className="text-sm text-muted-foreground">
                    开启界面过渡动画效果
                  </p>
                </div>
                <Switch
                  checked={display.enableAnimation}
                  onCheckedChange={(checked) =>
                    setDisplay({ ...display, enableAnimation: checked })
                  }
                />
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="text-lg flex items-center gap-2">
                <FileText className="h-5 w-5" />
                编辑器设置
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-6">
              <div className="flex items-center justify-between">
                <div>
                  <Label className="font-medium">自动保存</Label>
                  <p className="text-sm text-muted-foreground">
                    编辑时自动保存文档
                  </p>
                </div>
                <Switch
                  checked={editor.autoSave}
                  onCheckedChange={(checked) =>
                    setEditor({ ...editor, autoSave: checked })
                  }
                />
              </div>

              <Separator className="my-4" />

              <div className="flex items-center justify-between">
                <div>
                  <Label className="font-medium">拼写检查</Label>
                  <p className="text-sm text-muted-foreground">
                    对输入内容进行拼写检查
                  </p>
                </div>
                <Switch
                  checked={editor.spellCheck}
                  onCheckedChange={(checked) =>
                    setEditor({ ...editor, spellCheck: checked })
                  }
                />
              </div>

              <Separator className="my-4" />

              <div className="flex items-center justify-between">
                <div>
                  <Label className="font-medium">自动换行</Label>
                  <p className="text-sm text-muted-foreground">
                    长文本自动换行显示
                  </p>
                </div>
                <Switch
                  checked={editor.wordWrap}
                  onCheckedChange={(checked) =>
                    setEditor({ ...editor, wordWrap: checked })
                  }
                />
              </div>

              <Separator className="my-4" />

              <div className="flex items-center justify-between">
                <div>
                  <Label className="font-medium">默认视图</Label>
                  <p className="text-sm text-muted-foreground">
                    打开文档时的默认显示模式
                  </p>
                </div>
                <Select
                  value={editor.defaultView}
                  onValueChange={(v) =>
                    setEditor({
                      ...editor,
                      defaultView: v as typeof editor.defaultView,
                    })
                  }
                >
                  <SelectTrigger className="w-32">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="edit">编辑模式</SelectItem>
                    <SelectItem value="preview">预览模式</SelectItem>
                    <SelectItem value="split">分屏模式</SelectItem>
                  </SelectContent>
                </Select>
              </div>

              <Separator className="my-4" />

              <div className="flex items-center justify-between">
                <div>
                  <Label className="font-medium">字体大小</Label>
                  <p className="text-sm text-muted-foreground">
                    编辑器字体大小
                  </p>
                </div>
                <Select
                  value={editor.fontSize.toString()}
                  onValueChange={(v) =>
                    setEditor({ ...editor, fontSize: parseInt(v) })
                  }
                >
                  <SelectTrigger className="w-24">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {[12, 14, 16, 18, 20].map((size) => (
                      <SelectItem key={size} value={size.toString()}>
                        {size}px
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="text-lg flex items-center gap-2">
                <Bell className="h-5 w-5" />
                通知设置
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-6">
              <div className="flex items-center justify-between">
                <div>
                  <Label className="font-medium">邮件通知</Label>
                  <p className="text-sm text-muted-foreground">
                    接收重要事件的邮件提醒
                  </p>
                </div>
                <Switch
                  checked={notifications.email}
                  onCheckedChange={(checked) =>
                    setNotifications({ ...notifications, email: checked })
                  }
                />
              </div>

              <Separator className="my-4" />

              <div className="flex items-center justify-between">
                <div>
                  <Label className="font-medium">浏览器通知</Label>
                  <p className="text-sm text-muted-foreground">
                    接收浏览器推送通知
                  </p>
                </div>
                <Switch
                  checked={notifications.browser}
                  onCheckedChange={(checked) =>
                    setNotifications({ ...notifications, browser: checked })
                  }
                />
              </div>

              <Separator className="my-4" />

              <div className="flex items-center justify-between">
                <div>
                  <Label className="font-medium">评论通知</Label>
                  <p className="text-sm text-muted-foreground">
                    收到新评论时通知
                  </p>
                </div>
                <Switch
                  checked={notifications.comment}
                  onCheckedChange={(checked) =>
                    setNotifications({ ...notifications, comment: checked })
                  }
                />
              </div>

              <Separator className="my-4" />

              <div className="flex items-center justify-between">
                <div>
                  <Label className="font-medium">提及通知</Label>
                  <p className="text-sm text-muted-foreground">
                    被 @ 提及时通知
                  </p>
                </div>
                <Switch
                  checked={notifications.mention}
                  onCheckedChange={(checked) =>
                    setNotifications({ ...notifications, mention: checked })
                  }
                />
              </div>

              <Separator className="my-4" />

              <div className="flex items-center justify-between">
                <div>
                  <Label className="font-medium">审阅通知</Label>
                  <p className="text-sm text-muted-foreground">
                    收到审阅请求时通知
                  </p>
                </div>
                <Switch
                  checked={notifications.review}
                  onCheckedChange={(checked) =>
                    setNotifications({ ...notifications, review: checked })
                  }
                />
              </div>

              <Separator className="my-4" />

              <div className="flex items-center justify-between">
                <div>
                  <Label className="font-medium">同步通知</Label>
                  <p className="text-sm text-muted-foreground">
                    数据源同步完成时通知
                  </p>
                </div>
                <Switch
                  checked={notifications.sync}
                  onCheckedChange={(checked) =>
                    setNotifications({ ...notifications, sync: checked })
                  }
                />
              </div>
            </CardContent>
          </Card>

          <div className="flex justify-end">
            <Button onClick={handleSave} disabled={isSaving}>
              {isSaving && (
                <Loader2 className="mr-2 h-4 w-4 animate-spin" />
              )}
              <Save className="mr-2 h-4 w-4" />
              保存全部设置
            </Button>
          </div>
        </div>
      </main>
    </div>
  );
}
