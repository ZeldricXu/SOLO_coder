package com.flowplatform.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flowplatform.common.R;
import com.flowplatform.entity.FormDefinition;
import com.flowplatform.entity.ProcessDefinition;
import com.flowplatform.entity.SysUser;
import com.flowplatform.service.FormDefinitionService;
import com.flowplatform.service.ProcessDefinitionService;
import com.flowplatform.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
@RequiredArgsConstructor
@RequestMapping("/process")
public class ProcessController {

    private final ProcessDefinitionService processDefinitionService;
    private final FormDefinitionService formDefinitionService;
    private final SysUserService sysUserService;

    @GetMapping
    public String list(Model model,
                       @RequestParam(defaultValue = "1") int pageNum,
                       @RequestParam(defaultValue = "10") int pageSize,
                       @RequestParam(required = false) String keyword,
                       @RequestParam(required = false) String category) {
        LambdaQueryWrapper<ProcessDefinition> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.and(w -> w.like(ProcessDefinition::getProcessName, keyword)
                    .or().like(ProcessDefinition::getProcessKey, keyword));
        }
        if (category != null && !category.isEmpty()) {
            wrapper.eq(ProcessDefinition::getCategory, category);
        }
        wrapper.orderByDesc(ProcessDefinition::getUpdateTime);
        Page<ProcessDefinition> page = processDefinitionService.page(new Page<>(pageNum, pageSize), wrapper);
        page.getRecords().forEach(p -> {
            if (p.getCreatorId() != null) {
                SysUser creator = sysUserService.getById(p.getCreatorId());
                if (creator != null) {
                    p.setCreatorName(creator.getUsername());
                }
            }
            if (p.getFormId() != null) {
                FormDefinition form = formDefinitionService.getById(p.getFormId());
                if (form != null) {
                    p.setFormName(form.getFormName());
                }
            }
        });
        model.addAttribute("page", page);
        model.addAttribute("keyword", keyword);
        model.addAttribute("category", category);
        return "process/list";
    }

    @GetMapping("/create")
    public String create(Model model) {
        List<FormDefinition> forms = formDefinitionService.list();
        model.addAttribute("forms", forms);
        return "process/designer";
    }

    @GetMapping("/edit/{id}")
    public String edit(@PathVariable Long id, Model model) {
        ProcessDefinition process = processDefinitionService.getById(id);
        model.addAttribute("process", process);
        List<FormDefinition> forms = formDefinitionService.list();
        model.addAttribute("forms", forms);
        return "process/designer";
    }

    @GetMapping("/view/{id}")
    public String view(@PathVariable Long id, Model model) {
        ProcessDefinition process = processDefinitionService.getById(id);
        if (process != null) {
            if (process.getFormId() != null) {
                FormDefinition form = formDefinitionService.getById(process.getFormId());
                if (form != null) {
                    process.setFormName(form.getFormName());
                }
            }
        }
        model.addAttribute("process", process);
        return "process/view";
    }

    @PostMapping("/save")
    @ResponseBody
    public R<?> save(@RequestBody ProcessDefinition processDefinition, Authentication auth) {
        SysUser user = sysUserService.findByUsername(auth.getName());
        if (processDefinition.getId() == null) {
            processDefinition.setCreatorId(user.getId());
            processDefinition.setStatus(0);
            processDefinition.setVersion(1);
            processDefinitionService.save(processDefinition);
        } else {
            processDefinitionService.updateById(processDefinition);
        }
        return R.ok(processDefinition.getId());
    }

    @PostMapping("/publish/{id}")
    @ResponseBody
    public R<?> publish(@PathVariable Long id) {
        ProcessDefinition process = processDefinitionService.getById(id);
        if (process == null) {
            return R.fail("Process not found");
        }
        boolean success;
        if (process.getStatus() != null && process.getStatus() == 1) {
            success = processDefinitionService.unpublishProcess(id);
        } else {
            success = processDefinitionService.publishProcess(id);
        }
        return success ? R.ok() : R.fail("Operation failed");
    }

    @PostMapping("/delete/{id}")
    @ResponseBody
    public R<?> delete(@PathVariable Long id) {
        boolean success = processDefinitionService.removeById(id);
        return success ? R.ok() : R.fail("Delete failed");
    }
}
