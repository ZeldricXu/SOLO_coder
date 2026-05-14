package com.crm.service;

import com.crm.common.IdGenerator;
import com.crm.dto.CustomerTagRequest;
import com.crm.dto.TagRequest;
import com.crm.entity.CustomerTag;
import com.crm.entity.Tag;
import com.crm.exception.BusinessException;
import com.crm.repository.CustomerTagRepository;
import com.crm.repository.TagRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class TagService {

    @Autowired
    private TagRepository tagRepository;

    @Autowired
    private CustomerTagRepository customerTagRepository;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private HistoryService historyService;

    @Transactional
    public Tag createTag(TagRequest request) {
        Tag tag = new Tag();
        tag.setTagId(IdGenerator.generateTagId());
        tag.setTagName(request.getTagName());
        tag.setTagType(request.getTagType() != null ? request.getTagType() : "industry");
        tag.setTagStatus(request.getTagStatus() != null ? request.getTagStatus() : "active");
        return tagRepository.save(tag);
    }

    public Tag getTagById(String tagId) {
        return tagRepository.findByTagId(tagId)
                .orElseThrow(() -> new BusinessException("标签不存在"));
    }

    public List<Tag> getAllTags() {
        return tagRepository.findAll();
    }

    public List<Tag> getActiveTags() {
        return tagRepository.findByTagStatus("active");
    }

    @Transactional
    public void assignTagToCustomer(CustomerTagRequest request) {
        customerService.getCustomerById(request.getCustomerId());
        getTagById(request.getTagId());

        CustomerTag customerTag = new CustomerTag();
        customerTag.setCustomerId(request.getCustomerId());
        customerTag.setTagId(request.getTagId());
        customerTagRepository.save(customerTag);

        historyService.recordHistory(
                request.getCustomerId(),
                "tag",
                request.getTagId(),
                "assign",
                "分配标签：" + request.getTagId(),
                null
        );
    }

    @Transactional
    public void removeTagFromCustomer(CustomerTagRequest request) {
        customerTagRepository.deleteByCustomerIdAndTagId(
                request.getCustomerId(),
                request.getTagId()
        );

        historyService.recordHistory(
                request.getCustomerId(),
                "tag",
                request.getTagId(),
                "remove",
                "移除标签：" + request.getTagId(),
                null
        );
    }

    public List<CustomerTag> getCustomerTags(String customerId) {
        return customerTagRepository.findByCustomerId(customerId);
    }
}
