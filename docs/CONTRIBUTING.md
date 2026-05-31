# 版本控制规范

## 分支命名规范

### 主分支
- `main` - 生产环境代码，稳定版本
- `develop` - 开发环境代码，最新功能

### 功能分支
```
feature/<功能模块>/<简短描述>
例: feature/ticket-assignment/add-load-balance
```

### 修复分支
```
hotfix/<问题描述>
例: hotfix/assignment-null-pointer
```

### 发布分支
```
release/<版本号>
例: release/1.0.0
```

## 提交信息规范

使用 Conventional Commits 规范：

```
<类型>(<范围>): <主题>

<正文>

<底部>
```

### 类型
- `feat` - 新功能
- `fix` - 修复 bug
- `docs` - 文档更新
- `style` - 代码风格调整（不影响代码运行）
- `refactor` - 重构（既不是新增功能，也不是修改 bug）
- `perf` - 性能优化
- `test` - 增加测试
- `build` - 构建系统或外部依赖的变动
- `ci` - CI 配置文件和脚本的变动
- `chore` - 其他不修改 src 或测试文件的变动

### 示例
```
feat(ticket-assignment): 添加负载均衡算法

- 实现基于权重的轮询算法
- 添加动态负载阈值配置
- 支持按技能匹配度加权

Closes: #123
```

## 代码审查清单

- [ ] 代码符合项目编码规范
- [ ] 有适当的单元测试
- [ ] 测试覆盖率达标
- [ ] 没有遗留的调试代码
- [ ] 文档已更新
- [ ] 没有性能问题
- [ ] 没有安全漏洞
