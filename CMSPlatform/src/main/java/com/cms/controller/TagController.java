package com.cms.controller;

import com.cms.dto.ApiResponse;
import com.cms.entity.Tag;
import com.cms.service.TagService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/tags")
public class TagController {

    @Autowired
    private TagService tagService;

    @PostMapping
    public ApiResponse<Tag> createTag(@RequestBody Tag tag) {
        Tag createdTag = tagService.createTag(tag);
        return ApiResponse.success(createdTag);
    }

    @PutMapping("/{tagId}")
    public ApiResponse<Tag> updateTag(@PathVariable String tagId, @RequestBody Tag tag) {
        Tag updatedTag = tagService.updateTag(tagId, tag);
        return ApiResponse.success(updatedTag);
    }

    @GetMapping("/{tagId}")
    public ApiResponse<Tag> getTag(@PathVariable String tagId) {
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

    @GetMapping("/type/{type}")
    public ApiResponse<List<Tag>> getTagsByType(@PathVariable String type) {
        List<Tag> tags = tagService.getTagsByType(type);
        return ApiResponse.success(tags);
    }

    @DeleteMapping("/{tagId}")
    public ApiResponse<Void> deleteTag(@PathVariable String tagId) {
        tagService.deleteTag(tagId);
        return ApiResponse.success(null);
    }
}
