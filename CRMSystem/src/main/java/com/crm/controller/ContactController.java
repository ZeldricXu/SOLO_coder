package com.crm.controller;

import com.crm.common.ApiResponse;
import com.crm.dto.ContactRequest;
import com.crm.entity.Contact;
import com.crm.service.ContactService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/contacts")
public class ContactController {

    @Autowired
    private ContactService contactService;

    @PostMapping
    public ApiResponse<Contact> createContact(@Valid @RequestBody ContactRequest request) {
        Contact contact = contactService.createContact(request);
        return ApiResponse.success(contact);
    }

    @GetMapping("/customer/{customerId}")
    public ApiResponse<List<Contact>> getCustomerContacts(@PathVariable String customerId) {
        List<Contact> contacts = contactService.getCustomerContacts(customerId);
        return ApiResponse.success(contacts);
    }

    @GetMapping("/customer/{customerId}/primary")
    public ApiResponse<Contact> getPrimaryContact(@PathVariable String customerId) {
        Contact contact = contactService.getPrimaryContact(customerId);
        return ApiResponse.success(contact);
    }

    @PutMapping("/{id}")
    public ApiResponse<Contact> updateContact(
            @PathVariable Long id,
            @RequestBody ContactRequest request) {
        Contact contact = contactService.updateContact(id, request);
        return ApiResponse.success(contact);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteContact(@PathVariable Long id) {
        contactService.deleteContact(id);
        return ApiResponse.success(null);
    }
}
