package com.designsystem.controller;

import com.designsystem.common.PageQuery;
import com.designsystem.service.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class PageController {

    private final ComponentService componentService;
    private final DesignTokenService tokenService;
    private final ApprovalService approvalService;
    private final ChangeTrackingService changeService;
    private final ObjectMapper objectMapper;

    public PageController(ComponentService componentService, DesignTokenService tokenService,
                          ApprovalService approvalService, ChangeTrackingService changeService,
                          ObjectMapper objectMapper) {
        this.componentService = componentService;
        this.tokenService = tokenService;
        this.approvalService = approvalService;
        this.changeService = changeService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/dashboard")
    public String dashboard(Model model) {
        model.addAttribute("pendingApprovals", approvalService.getPendingApprovals());
        return "dashboard";
    }

    @GetMapping("/components")
    public String components(Model model, PageQuery query,
                             @RequestParam(required = false) String category,
                             @RequestParam(required = false) String framework) {
        var page = componentService.getComponentPage(query, category, framework);
        model.addAttribute("page", page);
        model.addAttribute("category", category);
        model.addAttribute("framework", framework);
        try {
            model.addAttribute("componentsJson", objectMapper.writeValueAsString(page.getRecords()));
            model.addAttribute("totalJson", page.getTotal());
            model.addAttribute("currentPageJson", page.getCurrent());
            model.addAttribute("totalPagesJson", page.getPages());
        } catch (JsonProcessingException e) {
            model.addAttribute("componentsJson", "[]");
            model.addAttribute("totalJson", 0);
            model.addAttribute("currentPageJson", 1);
            model.addAttribute("totalPagesJson", 1);
        }
        return "components/list";
    }

    @GetMapping("/components/{id}")
    public String componentDetail(@PathVariable Long id, Model model) {
        model.addAttribute("component", componentService.getComponentById(id));
        return "components/detail";
    }

    @GetMapping("/components/new")
    public String newComponent(Model model) {
        model.addAttribute("component", new com.designsystem.entity.Component());
        return "components/form";
    }

    @GetMapping("/components/{id}/edit")
    public String editComponent(@PathVariable Long id, Model model) {
        model.addAttribute("component", componentService.getComponentById(id));
        return "components/form";
    }

    @GetMapping("/marketplace")
    public String marketplace(Model model, PageQuery query,
                              @RequestParam(required = false) String category,
                              @RequestParam(required = false) String framework) {
        model.addAttribute("page", componentService.getMarketplacePage(query, category, framework, null));
        model.addAttribute("category", category);
        model.addAttribute("framework", framework);
        return "marketplace/list";
    }

    @GetMapping("/tokens")
    public String tokens(Model model, PageQuery query,
                         @RequestParam(required = false) String tokenType,
                         @RequestParam(required = false) String tokenLevel,
                         @RequestParam(required = false) String category) {
        var page = tokenService.getTokenPage(query, tokenType, tokenLevel, category);
        var tokenTree = tokenService.getTokenTree();
        model.addAttribute("page", page);
        model.addAttribute("tokenTree", tokenTree);
        model.addAttribute("tokenType", tokenType);
        model.addAttribute("tokenLevel", tokenLevel);
        model.addAttribute("category", category);
        try {
            model.addAttribute("tokensJson", objectMapper.writeValueAsString(page.getRecords()));
            model.addAttribute("tokenTreeJson", objectMapper.writeValueAsString(tokenTree));
            model.addAttribute("totalJson", page.getTotal());
            model.addAttribute("currentPageJson", page.getCurrent());
            model.addAttribute("totalPagesJson", page.getPages());
        } catch (JsonProcessingException e) {
            model.addAttribute("tokensJson", "[]");
            model.addAttribute("tokenTreeJson", "[]");
            model.addAttribute("totalJson", 0);
            model.addAttribute("currentPageJson", 1);
            model.addAttribute("totalPagesJson", 1);
        }
        return "tokens/list";
    }

    @GetMapping("/tokens/{id}")
    public String tokenDetail(@PathVariable Long id, Model model) {
        model.addAttribute("token", tokenService.getTokenById(id));
        model.addAttribute("impact", tokenService.getTokenImpactAnalysis(id));
        return "tokens/detail";
    }

    @GetMapping("/tokens/new")
    public String newToken(Model model) {
        model.addAttribute("token", new com.designsystem.entity.DesignToken());
        model.addAttribute("parentTokens", tokenService.getTokenTree());
        return "tokens/form";
    }

    @GetMapping("/tokens/{id}/edit")
    public String editToken(@PathVariable Long id, Model model) {
        var token = tokenService.getTokenById(id);
        model.addAttribute("token", token);
        model.addAttribute("parentTokens", tokenService.getTokenTree());
        try {
            model.addAttribute("tokenJson", objectMapper.writeValueAsString(token));
        } catch (JsonProcessingException e) {
            model.addAttribute("tokenJson", "{}");
        }
        return "tokens/form";
    }

    @GetMapping("/approvals")
    public String approvals(Model model, PageQuery query,
                            @RequestParam(required = false) String status,
                            @RequestParam(required = false) String requestType) {
        model.addAttribute("page", approvalService.getApprovalPage(query, status, requestType));
        model.addAttribute("status", status);
        model.addAttribute("requestType", requestType);
        return "approvals/list";
    }

    @GetMapping("/approvals/{id}")
    public String approvalDetail(@PathVariable Long id, Model model) {
        model.addAttribute("approval", approvalService.getApprovalById(id));
        return "approvals/detail";
    }

    @GetMapping("/changelog")
    public String changelog(Model model, @RequestParam(required = false) Long componentId) {
        if (componentId != null) {
            model.addAttribute("changelogs", changeService.getChangelogsByComponentId(componentId));
        }
        model.addAttribute("pendingMigrations", changeService.getPendingMigrations());
        return "changelog/list";
    }

    @GetMapping("/codegen")
    public String codegen(Model model) {
        return "codegen/form";
    }

    @GetMapping("/preview/{versionId}")
    public String preview(@PathVariable Long versionId, Model model) {
        model.addAttribute("versionId", versionId);
        return "preview/iframe";
    }

    @GetMapping("/docs/search")
    public String docSearch() {
        return "docs/search";
    }
}
