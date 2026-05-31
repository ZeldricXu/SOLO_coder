package com.contraudit.wallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.contraudit.common.BusinessException;
import com.contraudit.common.ErrorCode;
import com.contraudit.wallet.dto.AddAddressBookRequest;
import com.contraudit.wallet.entity.AddressBook;
import com.contraudit.wallet.entity.AddressTag;
import com.contraudit.wallet.mapper.AddressBookMapper;
import com.contraudit.wallet.mapper.AddressTagMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AddressBookService {

    private final AddressBookMapper addressBookMapper;
    private final AddressTagMapper addressTagMapper;

    @Transactional(rollbackFor = Exception.class)
    public AddressBook addAddress(AddAddressBookRequest request) {
        AddressBook addressBook = new AddressBook();
        addressBook.setAddress(request.getAddress());
        addressBook.setChainType(request.getChainType());
        addressBook.setLabel(request.getLabel());
        addressBook.setDescription(request.getDescription());
        addressBook.setCategory(request.getCategory());
        addressBook.setIsWhitelist(request.getIsWhitelist() != null ? request.getIsWhitelist() : 0);
        addressBook.setIsBlacklist(request.getIsBlacklist() != null ? request.getIsBlacklist() : 0);

        addressBookMapper.insert(addressBook);
        log.info("Added address to book: {} - {}", request.getAddress(), request.getLabel());

        return addressBook;
    }

    public AddressBook getAddress(String id) {
        AddressBook addressBook = addressBookMapper.selectById(id);
        if (addressBook == null) {
            throw new BusinessException(ErrorCode.ADDRESS_NOT_FOUND);
        }
        return addressBook;
    }

    public List<AddressBook> listAddresses(String chainType, String category, Boolean whitelist, Boolean blacklist) {
        LambdaQueryWrapper<AddressBook> wrapper = new LambdaQueryWrapper<>();
        if (chainType != null) {
            wrapper.eq(AddressBook::getChainType, chainType);
        }
        if (category != null) {
            wrapper.eq(AddressBook::getCategory, category);
        }
        if (whitelist != null && whitelist) {
            wrapper.eq(AddressBook::getIsWhitelist, 1);
        }
        if (blacklist != null && blacklist) {
            wrapper.eq(AddressBook::getIsBlacklist, 1);
        }
        return addressBookMapper.selectList(wrapper);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteAddress(String id) {
        addressBookMapper.deleteById(id);
        log.info("Deleted address from book: {}", id);
    }

    @Transactional(rollbackFor = Exception.class)
    public AddressTag addTag(String addressBookId, String tagName, String tagValue) {
        AddressBook addressBook = addressBookMapper.selectById(addressBookId);
        if (addressBook == null) {
            throw new BusinessException(ErrorCode.ADDRESS_NOT_FOUND);
        }

        AddressTag tag = new AddressTag();
        tag.setAddressBookId(addressBookId);
        tag.setTagName(tagName);
        tag.setTagValue(tagValue);

        addressTagMapper.insert(tag);
        log.info("Added tag: {} to address: {}", tagName, addressBookId);

        return tag;
    }

    public List<AddressTag> getAddressTags(String addressBookId) {
        LambdaQueryWrapper<AddressTag> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AddressTag::getAddressBookId, addressBookId);
        return addressTagMapper.selectList(wrapper);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteTag(String tagId) {
        addressTagMapper.deleteById(tagId);
        log.info("Deleted tag: {}", tagId);
    }
}
