package com.flowplatform.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flowplatform.common.R;
import com.flowplatform.entity.FormDefinition;
import com.flowplatform.entity.SysUser;
import com.flowplatform.service.FormDefinitionService;
import com.flowplatform.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequiredArgsConstructor
@RequestMapping("/form")
public class FormController {

    private final FormDefinitionService formDefinitionService;
    private final SysUserService sysUserService;

    @GetMapping
    public String list(Model model,
                       @RequestParam(defaultValue = "1") int pageNum,
                       @RequestParam(defaultValue = "10") int pageSize,
                       @RequestParam(required = false) String keyword,
                       @RequestParam(required = false) String category) {
        LambdaQueryWrapper<FormDefinition> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.and(w -> w.like(FormDefinition::getFormName, keyword)
                    .or().like(FormDefinition::getFormKey, keyword));
        }
        if (category != null && !category.isEmpty()) {
            wrapper.eq(FormDefinition::getCategory, category);
        }
        wrapper.orderByDesc(FormDefinition::getUpdateTime);
        Page<FormDefinition> page = formDefinitionService.page(new Page<>(pageNum, pageSize), wrapper);
        page.getRecords().forEach(f -> {
            if (f.getCreatorId() != null) {
                SysUser creator = sysUserService.getById(f.getCreatorId());
                if (creator != null) {
                    f.setCreatorName(creator.getUsername());
                }
            }
        });
        model.addAttribute("page", page);
        model.addAttribute("keyword", keyword);
        model.addAttribute("category", category);
        return "form/list";
    }

    @GetMapping("/create")
    public String create() {
        return "form/designer";
    }

    @GetMapping("/edit/{id}")
    public String edit(@PathVariable Long id, Model model) {
        FormDefinition form = formDefinitionService.getById(id);
        model.addAttribute("form", form);
        return "form/designer";
    }

    @GetMapping("/view/{id}")
    public String view(@PathVariable Long id, Model model) {
        FormDefinition form = formDefinitionService.getById(id);
        model.addAttribute("form", form);
        return "form/view";
    }

    @PostMapping("/save")
    @ResponseBody
    public R<?> save(@RequestBody FormDefinition formDefinition, Authentication auth) {
        SysUser user = sysUserService.findByUsername(auth.getName());
        if (formDefinition.getId() == null) {
            formDefinition.setCreatorId(user.getId());
            formDefinition.setStatus(0);
            formDefinition.setVersion(1);
            formDefinitionService.save(formDefinition);
        } else {
            formDefinitionService.updateById(formDefinition);
        }
        return R.ok(formDefinition.getId());
    }

    @PostMapping("/publish/{id}")
    @ResponseBody
    public R<?> publish(@PathVariable Long id) {
        boolean success = formDefinitionService.publishForm(id);
        return success ? R.ok() : R.fail("Publish failed");
    }

    @PostMapping("/delete/{id}")
    @ResponseBody
    public R<?> delete(@PathVariable Long id) {
        boolean success = formDefinitionService.removeById(id);
        return success ? R.ok() : R.fail("Delete failed");
    }

    @GetMapping("/render/{id}")
    public String render(@PathVariable Long id, Model model) {
        FormDefinition form = formDefinitionService.getById(id);
        model.addAttribute("form", form);
        return "form/render";
    }
}
