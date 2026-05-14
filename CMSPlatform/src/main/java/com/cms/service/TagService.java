package com.cms.service;

import com.cms.entity.Tag;
import com.cms.exception.BusinessException;
import com.cms.repository.TagRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class TagService {

    @Autowired
    private TagRepository tagRepository;

    @Transactional
    public Tag createTag(Tag tag) {
        if (tagRepository.findByTagName(tag.getTagName()).isPresent()) {
            throw new BusinessException(400, "标签名称已存在");
        }

        Tag newTag = new Tag();
        newTag.setTagId("tag_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        newTag.setTagName(tag.getTagName());
        newTag.setTagType(tag.getTagType() != null ? tag.getTagType() : "default");
        newTag.setTagColor(tag.getTagColor());
        newTag.setTagDescription(tag.getTagDescription());
        newTag.setTagStatus(tag.getTagStatus() != null ? tag.getTagStatus() : "active");
        newTag.setUseCount(0L);

        return tagRepository.save(newTag);
    }

    @Transactional
    public Tag updateTag(String tagId, Tag tag) {
        Tag existingTag = getTagById(tagId);

        if (tag.getTagName() != null) {
            Optional<Tag> duplicate = tagRepository.findByTagName(tag.getTagName());
            if (duplicate.isPresent() && !duplicate.get().getTagId().equals(tagId)) {
                throw new BusinessException(400, "标签名称已存在");
            }
            existingTag.setTagName(tag.getTagName());
        }
        if (tag.getTagType() != null) {
            existingTag.setTagType(tag.getTagType());
        }
        if (tag.getTagColor() != null) {
            existingTag.setTagColor(tag.getTagColor());
        }
        if (tag.getTagDescription() != null) {
            existingTag.setTagDescription(tag.getTagDescription());
        }
        if (tag.getTagStatus() != null) {
            existingTag.setTagStatus(tag.getTagStatus());
        }

        return tagRepository.save(existingTag);
    }

    public Tag getTagById(String tagId) {
        return tagRepository.findById(tagId)
                .orElseThrow(() -> new BusinessException(404, "标签不存在"));
    }

    public List<Tag> getAllTags() {
        return tagRepository.findAll();
    }

    public List<Tag> getActiveTags() {
        return tagRepository.findByTagStatus("active");
    }

    public List<Tag> getTagsByType(String type) {
        return tagRepository.findByTagType(type);
    }

    @Transactional
    public void deleteTag(String tagId) {
        Tag tag = getTagById(tagId);
        if (tag.getUseCount() > 0) {
            throw new BusinessException(400, "该标签已被使用，无法删除");
        }
        tagRepository.delete(tag);
    }

    @Transactional
    public void incrementUseCount(String tagId) {
        Tag tag = getTagById(tagId);
        tag.setUseCount(tag.getUseCount() + 1);
        tagRepository.save(tag);
    }

    @Transactional
    public void decrementUseCount(String tagId) {
        Tag tag = getTagById(tagId);
        tag.setUseCount(Math.max(0, tag.getUseCount() - 1));
        tagRepository.save(tag);
    }
}
