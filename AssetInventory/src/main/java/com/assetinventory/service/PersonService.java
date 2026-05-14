package com.assetinventory.service;

import com.assetinventory.entity.InventoryPerson;
import com.assetinventory.exception.InventoryException;
import com.assetinventory.repository.InventoryPersonRepository;
import com.assetinventory.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class PersonService {

    private final InventoryPersonRepository personRepository;

    @Autowired
    public PersonService(InventoryPersonRepository personRepository) {
        this.personRepository = personRepository;
    }

    public InventoryPerson createPerson(String personName, String personDepartment) {
        InventoryPerson person = new InventoryPerson();
        person.setPersonId(IdGenerator.generatePersonId());
        person.setPersonName(personName);
        person.setPersonDepartment(personDepartment);
        person.setPersonStatus("active");
        person.setTaskCount(0);
        person.setCreatedAt(IdGenerator.now());

        return personRepository.save(person);
    }

    public List<InventoryPerson> getAllPersons() {
        return personRepository.findAll();
    }

    public List<InventoryPerson> getActivePersons() {
        return personRepository.findByPersonStatus("active");
    }

    public Optional<InventoryPerson> getPersonById(String personId) {
        return personRepository.findByPersonId(personId);
    }

    public InventoryPerson getPersonByIdOrThrow(String personId) {
        return personRepository.findByPersonId(personId)
                .orElseThrow(() -> new InventoryException(404, "盘点人员不存在: " + personId));
    }

    public InventoryPerson assignTaskToPerson() {
        List<InventoryPerson> activePersons = getActivePersons();
        if (activePersons.isEmpty()) {
            throw new InventoryException(400, "没有可用的盘点人员");
        }

        return activePersons.stream()
                .filter(p -> "active".equals(p.getPersonStatus()))
                .filter(p -> p.getTaskCount() < 10)
                .min(Comparator.comparingInt(InventoryPerson::getTaskCount))
                .orElseThrow(() -> new InventoryException(400, "所有人员任务已满"));
    }

    public InventoryPerson incrementTaskCount(String personId) {
        InventoryPerson person = getPersonByIdOrThrow(personId);
        person.setTaskCount(person.getTaskCount() + 1);
        return personRepository.save(person);
    }

    public InventoryPerson decrementTaskCount(String personId) {
        InventoryPerson person = getPersonByIdOrThrow(personId);
        if (person.getTaskCount() > 0) {
            person.setTaskCount(person.getTaskCount() - 1);
        }
        return personRepository.save(person);
    }

    public InventoryPerson updatePersonStatus(String personId, String status) {
        InventoryPerson person = getPersonByIdOrThrow(personId);
        person.setPersonStatus(status);
        return personRepository.save(person);
    }
}
