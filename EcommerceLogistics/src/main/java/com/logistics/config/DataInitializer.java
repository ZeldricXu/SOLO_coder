package com.logistics.config;

import com.logistics.constant.LogisticsConstants;
import com.logistics.entity.Courier;
import com.logistics.entity.Station;
import com.logistics.service.CourierService;
import com.logistics.service.StationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final StationService stationService;
    private final CourierService courierService;

    @Override
    public void run(String... args) {
        initStations();
        initCouriers();
        log.info("数据初始化完成");
    }

    private void initStations() {
        if (!stationService.getAllStations().isEmpty()) {
            log.info("网点数据已存在，跳过初始化");
            return;
        }

        Station station1 = new Station();
        station1.setStationId("station_001");
        station1.setStationName("朝阳区配送中心");
        station1.setStationAddress("北京市朝阳区建国路88号");
        station1.setStationRegion("朝阳区");
        station1.setStationCapacity(100);
        station1.setStationStatus(LogisticsConstants.STATION_STATUS_ACTIVE);
        stationService.createStation(station1);

        Station station2 = new Station();
        station2.setStationId("station_002");
        station2.setStationName("海淀区配送中心");
        station2.setStationAddress("北京市海淀区中关村大街1号");
        station2.setStationRegion("海淀区");
        station2.setStationCapacity(80);
        station2.setStationStatus(LogisticsConstants.STATION_STATUS_ACTIVE);
        stationService.createStation(station2);

        Station station3 = new Station();
        station3.setStationId("station_003");
        station3.setStationName("西城区配送中心");
        station3.setStationAddress("北京市西城区西单北大街1号");
        station3.setStationRegion("西城区");
        station3.setStationCapacity(60);
        station3.setStationStatus(LogisticsConstants.STATION_STATUS_ACTIVE);
        stationService.createStation(station3);

        log.info("初始化3个配送网点");
    }

    private void initCouriers() {
        if (!courierService.getAllCouriers().isEmpty()) {
            log.info("配送员数据已存在，跳过初始化");
            return;
        }

        Courier courier1 = new Courier();
        courier1.setCourierId("courier_001");
        courier1.setCourierName("张三");
        courier1.setCourierPhone("13800138001");
        courier1.setCourierStation("station_001");
        courier1.setCourierStatus(LogisticsConstants.COURIER_STATUS_AVAILABLE);
        courier1.setCourierCapacity(50);
        courier1.setCourierRating(4.8);
        courierService.createCourier(courier1);

        Courier courier2 = new Courier();
        courier2.setCourierId("courier_002");
        courier2.setCourierName("李四");
        courier2.setCourierPhone("13800138002");
        courier2.setCourierStation("station_001");
        courier2.setCourierStatus(LogisticsConstants.COURIER_STATUS_AVAILABLE);
        courier2.setCourierCapacity(40);
        courier2.setCourierRating(4.5);
        courierService.createCourier(courier2);

        Courier courier3 = new Courier();
        courier3.setCourierId("courier_003");
        courier3.setCourierName("王五");
        courier3.setCourierPhone("13800138003");
        courier3.setCourierStation("station_002");
        courier3.setCourierStatus(LogisticsConstants.COURIER_STATUS_AVAILABLE);
        courier3.setCourierCapacity(45);
        courier3.setCourierRating(4.9);
        courierService.createCourier(courier3);

        Courier courier4 = new Courier();
        courier4.setCourierId("courier_004");
        courier4.setCourierName("赵六");
        courier4.setCourierPhone("13800138004");
        courier4.setCourierStation("station_002");
        courier4.setCourierStatus(LogisticsConstants.COURIER_STATUS_AVAILABLE);
        courier4.setCourierCapacity(35);
        courier4.setCourierRating(4.3);
        courierService.createCourier(courier4);

        Courier courier5 = new Courier();
        courier5.setCourierId("courier_005");
        courier5.setCourierName("钱七");
        courier5.setCourierPhone("13800138005");
        courier5.setCourierStation("station_003");
        courier5.setCourierStatus(LogisticsConstants.COURIER_STATUS_AVAILABLE);
        courier5.setCourierCapacity(30);
        courier5.setCourierRating(4.7);
        courierService.createCourier(courier5);

        log.info("初始化5个配送员");
    }
}
