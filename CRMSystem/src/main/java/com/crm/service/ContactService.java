package com.crm.service;

import com.crm.dto.ContactRequest;
import com.crm.entity.Contact;
import com.crm.repository.ContactRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ContactService {

    @Autowired
    private ContactRepository contactRepository;

    @Autowired
    private CustomerService customerService;

    @Transactional
    public Contact createContact(ContactRequest request) {
        customerService.getCustomerById(request.getCustomerId());

        Contact contact = new Contact();
        contact.setCustomerId(request.getCustomerId());
        contact.setContactName(request.getContactName());
        contact.setContactPhone(request.getContactPhone());
        contact.setContactEmail(request.getContactEmail());
        contact.setContactPosition(request.getContactPosition());
        contact.setIsPrimary(request.getIsPrimary() != null ? request.getIsPrimary() : false);

        return contactRepository.save(contact);
    }

    public List<Contact> getCustomerContacts(String customerId) {
        return contactRepository.findByCustomerId(customerId);
    }

    public Contact getPrimaryContact(String customerId) {
        List<Contact> primaryContacts = contactRepository.findByCustomerIdAndIsPrimary(customerId, true);
        return primaryContacts.isEmpty() ? null : primaryContacts.get(0);
    }

    @Transactional
    public Contact updateContact(Long id, ContactRequest request) {
        Contact contact = contactRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("联系人不存在"));

        if (request.getContactName() != null) {
            contact.setContactName(request.getContactName());
        }
        if (request.getContactPhone() != null) {
            contact.setContactPhone(request.getContactPhone());
        }
        if (request.getContactEmail() != null) {
            contact.setContactEmail(request.getContactEmail());
        }
        if (request.getContactPosition() != null) {
            contact.setContactPosition(request.getContactPosition());
        }
        if (request.getIsPrimary() != null) {
            contact.setIsPrimary(request.getIsPrimary());
        }

        return contactRepository.save(contact);
    }

    @Transactional
    public void deleteContact(Long id) {
        contactRepository.deleteById(id);
    }
}
