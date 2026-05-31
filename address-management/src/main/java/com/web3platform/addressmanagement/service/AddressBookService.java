package com.web3platform.addressmanagement.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.web3platform.addressmanagement.model.AddressBookEntryRequest;
import com.web3platform.addressmanagement.model.AddressBatchTagRequest;
import com.web3platform.persistence.mapper.AddressEntryMapper;
import com.web3platform.persistence.model.entity.AddressEntry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class AddressBookService {

    private final AddressEntryMapper addressEntryMapper;
    private final AddressValidator addressValidator;

    public AddressBookService(AddressEntryMapper addressEntryMapper, AddressValidator addressValidator) {
        this.addressEntryMapper = addressEntryMapper;
        this.addressValidator = addressValidator;
    }

    @Transactional
    public AddressEntry addEntry(AddressBookEntryRequest request) {
        String normalizedAddress = addressValidator.normalize(request.getAddress(), request.getChainType());

        LambdaQueryWrapper<AddressEntry> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AddressEntry::getAddress, normalizedAddress)
                .eq(AddressEntry::getChainType, request.getChainType());
        AddressEntry existing = addressEntryMapper.selectOne(wrapper);

        if (existing != null) {
            throw new IllegalArgumentException("Address already exists in address book: " + normalizedAddress);
        }

        AddressEntry entry = new AddressEntry();
        entry.setAddress(normalizedAddress);
        entry.setChainType(request.getChainType());
        entry.setLabel(request.getLabel());
        entry.setTags(serializeTags(request.getTags()));
        entry.setNote(request.getNote());
        entry.setCreatedAt(LocalDateTime.now());
        entry.setUpdatedAt(LocalDateTime.now());

        addressEntryMapper.insert(entry);
        return entry;
    }

    @Transactional
    public AddressEntry updateLabel(String address, String chainType, String label) {
        String normalizedAddress = addressValidator.normalize(address, chainType);

        AddressEntry entry = getEntryInternal(normalizedAddress, chainType);
        entry.setLabel(label);
        entry.setUpdatedAt(LocalDateTime.now());

        addressEntryMapper.updateById(entry);
        return entry;
    }

    @Transactional
    public AddressEntry addTag(String address, String chainType, String tag) {
        String normalizedAddress = addressValidator.normalize(address, chainType);

        AddressEntry entry = getEntryInternal(normalizedAddress, chainType);
        List<String> tags = deserializeTags(entry.getTags());

        if (!tags.contains(tag)) {
            tags.add(tag);
            entry.setTags(serializeTags(tags));
            entry.setUpdatedAt(LocalDateTime.now());
            addressEntryMapper.updateById(entry);
        }

        return entry;
    }

    @Transactional
    public AddressEntry removeTag(String address, String chainType, String tag) {
        String normalizedAddress = addressValidator.normalize(address, chainType);

        AddressEntry entry = getEntryInternal(normalizedAddress, chainType);
        List<String> tags = deserializeTags(entry.getTags());

        if (tags.remove(tag)) {
            entry.setTags(serializeTags(tags));
            entry.setUpdatedAt(LocalDateTime.now());
            addressEntryMapper.updateById(entry);
        }

        return entry;
    }

    @Transactional
    public void batchUpdateTags(AddressBatchTagRequest request) {
        for (String address : request.getAddresses()) {
            if ("ADD".equalsIgnoreCase(request.getOperation())) {
                for (String chainType : List.of("ETH", "BSC", "POLYGON", "BTC")) {
                    try {
                        if (addressValidator.validate(address, chainType)) {
                            addTag(address, chainType, request.getTag());
                            break;
                        }
                    } catch (Exception e) {
                        continue;
                    }
                }
            } else if ("REMOVE".equalsIgnoreCase(request.getOperation())) {
                for (String chainType : List.of("ETH", "BSC", "POLYGON", "BTC")) {
                    try {
                        if (addressValidator.validate(address, chainType)) {
                            removeTag(address, chainType, request.getTag());
                            break;
                        }
                    } catch (Exception e) {
                        continue;
                    }
                }
            } else {
                throw new IllegalArgumentException("Invalid operation: " + request.getOperation());
            }
        }
    }

    public List<AddressEntry> searchByTag(String tag) {
        LambdaQueryWrapper<AddressEntry> wrapper = new LambdaQueryWrapper<>();
        wrapper.apply("FIND_IN_SET({0}, tags) > 0", tag);
        return addressEntryMapper.selectList(wrapper);
    }

    public List<AddressEntry> searchByLabel(String keyword) {
        LambdaQueryWrapper<AddressEntry> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(AddressEntry::getLabel, keyword);
        return addressEntryMapper.selectList(wrapper);
    }

    public AddressEntry getEntry(String address, String chainType) {
        String normalizedAddress = addressValidator.normalize(address, chainType);
        return getEntryInternal(normalizedAddress, chainType);
    }

    public IPage<AddressEntry> listEntries(String chainType, int page, int size) {
        LambdaQueryWrapper<AddressEntry> wrapper = new LambdaQueryWrapper<>();
        if (chainType != null && !chainType.isEmpty()) {
            wrapper.eq(AddressEntry::getChainType, chainType);
        }
        wrapper.orderByDesc(AddressEntry::getCreatedAt);

        Page<AddressEntry> pageParam = new Page<>(page, size);
        return addressEntryMapper.selectPage(pageParam, wrapper);
    }

    @Transactional
    public void deleteEntry(String address, String chainType) {
        String normalizedAddress = addressValidator.normalize(address, chainType);

        LambdaQueryWrapper<AddressEntry> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AddressEntry::getAddress, normalizedAddress)
                .eq(AddressEntry::getChainType, chainType);

        int deleted = addressEntryMapper.delete(wrapper);
        if (deleted == 0) {
            throw new IllegalArgumentException("Address entry not found");
        }
    }

    private AddressEntry getEntryInternal(String normalizedAddress, String chainType) {
        LambdaQueryWrapper<AddressEntry> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AddressEntry::getAddress, normalizedAddress)
                .eq(AddressEntry::getChainType, chainType);

        AddressEntry entry = addressEntryMapper.selectOne(wrapper);
        if (entry == null) {
            throw new IllegalArgumentException("Address entry not found for address: " + normalizedAddress);
        }
        return entry;
    }

    private String serializeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        return String.join(",", tags);
    }

    private List<String> deserializeTags(String tagsStr) {
        if (tagsStr == null || tagsStr.isEmpty()) {
            return new java.util.ArrayList<>();
        }
        return Arrays.stream(tagsStr.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    public List<String> getEntryTags(AddressEntry entry) {
        return deserializeTags(entry.getTags());
    }
}
