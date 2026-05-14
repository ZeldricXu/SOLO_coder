package com.assetinventory.controller;

import com.assetinventory.dto.ApiResponse;
import com.assetinventory.entity.InventoryPerson;
import com.assetinventory.service.PersonService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/persons")
public class PersonController {

    private final PersonService personService;

    @Autowired
    public PersonController(PersonService personService) {
        this.personService = personService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<InventoryPerson>> createPerson(@RequestBody InventoryPerson person) {
        InventoryPerson created = personService.createPerson(
                person.getPersonName(),
                person.getPersonDepartment()
        );
        return ResponseEntity.ok(ApiResponse.success(created));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<InventoryPerson>>> getAllPersons() {
        List<InventoryPerson> persons = personService.getAllPersons();
        return ResponseEntity.ok(ApiResponse.success(persons));
    }

    @GetMapping("/active")
    public ResponseEntity<ApiResponse<List<InventoryPerson>>> getActivePersons() {
        List<InventoryPerson> persons = personService.getActivePersons();
        return ResponseEntity.ok(ApiResponse.success(persons));
    }

    @GetMapping("/{personId}")
    public ResponseEntity<ApiResponse<InventoryPerson>> getPersonById(@PathVariable String personId) {
        return personService.getPersonById(personId)
                .map(person -> ResponseEntity.ok(ApiResponse.success(person)))
                .orElse(ResponseEntity.ok(ApiResponse.error(404, "盘点人员不存在")));
    }
}
