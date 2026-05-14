package com.crm.controller;

import com.crm.common.ApiResponse;
import com.crm.dto.CustomerTagRequest;
import com.crm.dto.TagRequest;
import com.crm.entity.CustomerTag;
import com.crm.entity.Tag;
import com.crm.service.TagService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/tags")
public class TagController {

    @Autowired
    private TagService tagService;

    @PostMapping
    public ApiResponse<Tag> createTag(@Valid @RequestBody TagRequest request) {
        Tag tag = tagService.createTag(request);
        return ApiResponse.success(tag);
    }

    @GetMapping("/{tagId}")
    public ApiResponse<Tag> getTagById(@PathVariable String tagId) {
        Tag tag = tagService.getTagById(tagId);
        return ApiResponse.success(tag);
    }

    @GetMapping
    public ApiResponse<List<Tag>> getAllTags() {
        List<Tag> tags = tagService.getAllTags();
        return ApiResponse.success(tags);
    }

    @GetMapping("/active")
    public ApiResponse<List<Tag>> getActiveTags() {
        List<Tag> tags = tagService.getActiveTags();
        return ApiResponse.success(tags);
    }

    @PostMapping("/assign")
    public ApiResponse<Void> assignTagToCustomer(@Valid @RequestBody CustomerTagRequest request) {
        tagService.assignTagToCustomer(request);
        return ApiResponse.success(null);
    }

    @DeleteMapping("/remove")
    public ApiResponse<Void> removeTagFromCustomer(@Valid @RequestBody CustomerTagRequest request) {
        tagService.removeTagFromCustomer(request);
        return ApiResponse.success(null);
    }

    @GetMapping("/customer/{customerId}")
    public ApiResponse<List<CustomerTag>> getCustomerTags(@PathVariable String customerId) {
        List<CustomerTag> tags = tagService.getCustomerTags(customerId);
        return ApiResponse.success(tags);
    }
}
