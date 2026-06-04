package com.flowplatform.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flowplatform.common.R;
import com.flowplatform.entity.QuickComment;
import com.flowplatform.entity.SysUser;
import com.flowplatform.mapper.QuickCommentMapper;
import com.flowplatform.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/quick-comments")
public class QuickCommentController {

    private final QuickCommentMapper quickCommentMapper;
    private final SysUserService sysUserService;

    @GetMapping
    public R<List<QuickComment>> list(Authentication auth) {
        SysUser user = sysUserService.findByUsername(auth.getName());
        List<QuickComment> comments = quickCommentMapper.selectList(
                new LambdaQueryWrapper<QuickComment>()
                        .eq(QuickComment::getUserId, user.getId())
                        .orderByAsc(QuickComment::getSortOrder));
        return R.ok(comments);
    }

    @PostMapping
    public R<?> add(@RequestBody QuickComment quickComment, Authentication auth) {
        SysUser user = sysUserService.findByUsername(auth.getName());
        quickComment.setUserId(user.getId());
        if (quickComment.getSortOrder() == null) {
            Long count = quickCommentMapper.selectCount(
                    new LambdaQueryWrapper<QuickComment>()
                            .eq(QuickComment::getUserId, user.getId()));
            quickComment.setSortOrder(count.intValue() + 1);
        }
        quickCommentMapper.insert(quickComment);
        return R.ok(quickComment);
    }

    @DeleteMapping("/{id}")
    public R<?> delete(@PathVariable Long id, Authentication auth) {
        SysUser user = sysUserService.findByUsername(auth.getName());
        QuickComment existing = quickCommentMapper.selectById(id);
        if (existing == null || !existing.getUserId().equals(user.getId())) {
            return R.fail("无权删除此常用语");
        }
        quickCommentMapper.deleteById(id);
        return R.ok();
    }
}
