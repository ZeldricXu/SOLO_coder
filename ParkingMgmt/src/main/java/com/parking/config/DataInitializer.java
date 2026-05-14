package com.parking.config;

import com.parking.entity.ParkingLot;
import com.parking.entity.ParkingSpace;
import com.parking.repository.ParkingLotRepository;
import com.parking.repository.ParkingSpaceRepository;
import com.parking.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DataInitializer implements CommandLineRunner {

    @Autowired
    private ParkingLotRepository parkingLotRepository;

    @Autowired
    private ParkingSpaceRepository parkingSpaceRepository;

    @Override
    @Transactional
    public void run(String... args) {
        if (parkingLotRepository.count() == 0) {
            initializeDefaultData();
        }
    }

    private void initializeDefaultData() {
        ParkingLot parkingLot1 = new ParkingLot();
        parkingLot1.setParkingId(IdGenerator.generateParkingId());
        parkingLot1.setName("中央停车场");
        parkingLot1.setAddress("北京市朝阳区中央大街100号");
        parkingLot1.setTotalSpaces(50);
        parkingLot1.setHourlyRate(10.0);
        parkingLot1.setChargingType("hourly");
        parkingLotRepository.save(parkingLot1);

        ParkingLot parkingLot2 = new ParkingLot();
        parkingLot2.setParkingId(IdGenerator.generateParkingId());
        parkingLot2.setName("商场地下停车场");
        parkingLot2.setAddress("北京市海淀区购物中心B2层");
        parkingLot2.setTotalSpaces(100);
        parkingLot2.setHourlyRate(8.0);
        parkingLot2.setChargingType("hourly");
        parkingLotRepository.save(parkingLot2);

        for (int i = 1; i <= 10; i++) {
            ParkingSpace space = new ParkingSpace();
            space.setSpaceId(IdGenerator.generateSpaceId());
            space.setParkingLot(parkingLot1);
            space.setSpaceNumber(String.format("A%03d", i));
            space.setSpaceType("standard");
            space.setSpaceStatus("available");
            space.setSpacePrice(10.0);
            parkingSpaceRepository.save(space);
        }

        for (int i = 1; i <= 5; i++) {
            ParkingSpace space = new ParkingSpace();
            space.setSpaceId(IdGenerator.generateSpaceId());
            space.setParkingLot(parkingLot1);
            space.setSpaceNumber(String.format("B%03d", i));
            space.setSpaceType("vip");
            space.setSpaceStatus("available");
            space.setSpacePrice(20.0);
            parkingSpaceRepository.save(space);
        }

        for (int i = 1; i <= 15; i++) {
            ParkingSpace space = new ParkingSpace();
            space.setSpaceId(IdGenerator.generateSpaceId());
            space.setParkingLot(parkingLot2);
            space.setSpaceNumber(String.format("C%03d", i));
            space.setSpaceType("standard");
            space.setSpaceStatus("available");
            space.setSpacePrice(8.0);
            parkingSpaceRepository.save(space);
        }
    }
}
