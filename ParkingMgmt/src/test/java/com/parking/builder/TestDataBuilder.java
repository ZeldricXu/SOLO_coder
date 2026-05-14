package com.parking.builder;

import com.parking.entity.*;
import com.parking.dto.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class TestDataBuilder {

    private TestDataBuilder() {
    }

    public static String generateId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static ParkingLotBuilder parkingLotBuilder() {
        return new ParkingLotBuilder();
    }

    public static ParkingSpaceBuilder parkingSpaceBuilder() {
        return new ParkingSpaceBuilder();
    }

    public static VehicleBuilder vehicleBuilder() {
        return new VehicleBuilder();
    }

    public static EntryRecordBuilder entryRecordBuilder() {
        return new EntryRecordBuilder();
    }

    public static ExitRecordBuilder exitRecordBuilder() {
        return new ExitRecordBuilder();
    }

    public static SettlementRecordBuilder settlementRecordBuilder() {
        return new SettlementRecordBuilder();
    }

    public static ReservationRecordBuilder reservationRecordBuilder() {
        return new ReservationRecordBuilder();
    }

    public static EntryRequestBuilder entryRequestBuilder() {
        return new EntryRequestBuilder();
    }

    public static ExitRequestBuilder exitRequestBuilder() {
        return new ExitRequestBuilder();
    }

    public static PaymentRequestBuilder paymentRequestBuilder() {
        return new PaymentRequestBuilder();
    }

    public static class ParkingLotBuilder {
        private String parkingId = generateId("parking");
        private String name = "测试停车场";
        private String address = "测试地址";
        private int totalSpaces = 100;
        private double hourlyRate = 10.0;
        private String chargingType = "hourly";
        private Double fixedFee = null;

        public ParkingLotBuilder parkingId(String parkingId) {
            this.parkingId = parkingId;
            return this;
        }

        public ParkingLotBuilder name(String name) {
            this.name = name;
            return this;
        }

        public ParkingLotBuilder address(String address) {
            this.address = address;
            return this;
        }

        public ParkingLotBuilder totalSpaces(int totalSpaces) {
            this.totalSpaces = totalSpaces;
            return this;
        }

        public ParkingLotBuilder hourlyRate(double hourlyRate) {
            this.hourlyRate = hourlyRate;
            return this;
        }

        public ParkingLotBuilder chargingType(String chargingType) {
            this.chargingType = chargingType;
            return this;
        }

        public ParkingLotBuilder fixedFee(Double fixedFee) {
            this.fixedFee = fixedFee;
            return this;
        }

        public ParkingLot build() {
            ParkingLot lot = new ParkingLot();
            lot.setParkingId(parkingId);
            lot.setName(name);
            lot.setAddress(address);
            lot.setTotalSpaces(totalSpaces);
            lot.setHourlyRate(hourlyRate);
            lot.setChargingType(chargingType);
            lot.setFixedFee(fixedFee);
            lot.setSpaces(new ArrayList<>());
            return lot;
        }

        public List<ParkingLot> buildMultiple(int count) {
            List<ParkingLot> lots = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                lots.add(parkingLotBuilder()
                        .parkingId(generateId("parking"))
                        .name(name + (i + 1))
                        .build());
            }
            return lots;
        }
    }

    public static class ParkingSpaceBuilder {
        private String spaceId = generateId("space");
        private String spaceNumber = "A001";
        private String spaceType = "standard";
        private String spaceStatus = "available";
        private double spacePrice = 10.0;
        private LocalDateTime occupiedTime = null;

        public ParkingSpaceBuilder spaceId(String spaceId) {
            this.spaceId = spaceId;
            return this;
        }

        public ParkingSpaceBuilder spaceNumber(String spaceNumber) {
            this.spaceNumber = spaceNumber;
            return this;
        }

        public ParkingSpaceBuilder spaceType(String spaceType) {
            this.spaceType = spaceType;
            return this;
        }

        public ParkingSpaceBuilder spaceStatus(String spaceStatus) {
            this.spaceStatus = spaceStatus;
            return this;
        }

        public ParkingSpaceBuilder spacePrice(double spacePrice) {
            this.spacePrice = spacePrice;
            return this;
        }

        public ParkingSpaceBuilder occupiedTime(LocalDateTime occupiedTime) {
            this.occupiedTime = occupiedTime;
            return this;
        }

        public ParkingSpace build() {
            ParkingSpace space = new ParkingSpace();
            space.setSpaceId(spaceId);
            space.setSpaceNumber(spaceNumber);
            space.setSpaceType(spaceType);
            space.setSpaceStatus(spaceStatus);
            space.setSpacePrice(spacePrice);
            space.setOccupiedTime(occupiedTime);
            return space;
        }

        public ParkingSpace buildWithParkingLot(ParkingLot parkingLot) {
            ParkingSpace space = build();
            space.setParkingLot(parkingLot);
            return space;
        }

        public List<ParkingSpace> buildAvailableSpaces(int count, ParkingLot parkingLot) {
            List<ParkingSpace> spaces = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                spaces.add(parkingSpaceBuilder()
                        .spaceId(generateId("space"))
                        .spaceNumber(String.format("A%03d", i + 1))
                        .spaceStatus("available")
                        .buildWithParkingLot(parkingLot));
            }
            return spaces;
        }

        public List<ParkingSpace> buildOccupiedSpaces(int count, ParkingLot parkingLot) {
            List<ParkingSpace> spaces = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                spaces.add(parkingSpaceBuilder()
                        .spaceId(generateId("space"))
                        .spaceNumber(String.format("B%03d", i + 1))
                        .spaceStatus("occupied")
                        .occupiedTime(LocalDateTime.now())
                        .buildWithParkingLot(parkingLot));
            }
            return spaces;
        }

        public List<ParkingSpace> buildVipSpaces(int count, ParkingLot parkingLot) {
            List<ParkingSpace> spaces = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                spaces.add(parkingSpaceBuilder()
                        .spaceId(generateId("space"))
                        .spaceNumber(String.format("V%03d", i + 1))
                        .spaceType("vip")
                        .spaceStatus("available")
                        .spacePrice(20.0)
                        .buildWithParkingLot(parkingLot));
            }
            return spaces;
        }
    }

    public static class VehicleBuilder {
        private String vehicleId = generateId("vehicle");
        private String vehicleNumber = "京A12345";
        private String vehicleType = "sedan";
        private String vehicleOwner = "张三";
        private String vehiclePhone = "13800138000";
        private String currentStatus = "idle";

        public VehicleBuilder vehicleId(String vehicleId) {
            this.vehicleId = vehicleId;
            return this;
        }

        public VehicleBuilder vehicleNumber(String vehicleNumber) {
            this.vehicleNumber = vehicleNumber;
            return this;
        }

        public VehicleBuilder vehicleType(String vehicleType) {
            this.vehicleType = vehicleType;
            return this;
        }

        public VehicleBuilder vehicleOwner(String vehicleOwner) {
            this.vehicleOwner = vehicleOwner;
            return this;
        }

        public VehicleBuilder vehiclePhone(String vehiclePhone) {
            this.vehiclePhone = vehiclePhone;
            return this;
        }

        public VehicleBuilder currentStatus(String currentStatus) {
            this.currentStatus = currentStatus;
            return this;
        }

        public Vehicle build() {
            Vehicle vehicle = new Vehicle();
            vehicle.setVehicleId(vehicleId);
            vehicle.setVehicleNumber(vehicleNumber);
            vehicle.setVehicleType(vehicleType);
            vehicle.setVehicleOwner(vehicleOwner);
            vehicle.setVehiclePhone(vehiclePhone);
            vehicle.setCurrentStatus(currentStatus);
            return vehicle;
        }

        public Vehicle buildStandardSedan() {
            return vehicleBuilder()
                    .vehicleNumber("京A12345")
                    .vehicleType("sedan")
                    .build();
        }

        public Vehicle buildVipVehicle() {
            return vehicleBuilder()
                    .vehicleNumber("京V88888")
                    .vehicleType("vip")
                    .vehicleOwner("VIP客户")
                    .build();
        }

        public List<Vehicle> buildMultiple(int count) {
            List<Vehicle> vehicles = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                vehicles.add(vehicleBuilder()
                        .vehicleId(generateId("vehicle"))
                        .vehicleNumber("京A" + String.format("%05d", i + 1))
                        .build());
            }
            return vehicles;
        }
    }

    public static class EntryRecordBuilder {
        private String entryId = generateId("entry");
        private String vehicleId = generateId("vehicle");
        private String spaceId = generateId("space");
        private String parkingId = generateId("parking");
        private String vehicleNumber = "京A12345";
        private String spaceNumber = "A001";
        private LocalDateTime entryTime = LocalDateTime.now();
        private String entryStatus = "parked";

        public EntryRecordBuilder entryId(String entryId) {
            this.entryId = entryId;
            return this;
        }

        public EntryRecordBuilder vehicleId(String vehicleId) {
            this.vehicleId = vehicleId;
            return this;
        }

        public EntryRecordBuilder spaceId(String spaceId) {
            this.spaceId = spaceId;
            return this;
        }

        public EntryRecordBuilder parkingId(String parkingId) {
            this.parkingId = parkingId;
            return this;
        }

        public EntryRecordBuilder vehicleNumber(String vehicleNumber) {
            this.vehicleNumber = vehicleNumber;
            return this;
        }

        public EntryRecordBuilder spaceNumber(String spaceNumber) {
            this.spaceNumber = spaceNumber;
            return this;
        }

        public EntryRecordBuilder entryTime(LocalDateTime entryTime) {
            this.entryTime = entryTime;
            return this;
        }

        public EntryRecordBuilder entryStatus(String entryStatus) {
            this.entryStatus = entryStatus;
            return this;
        }

        public EntryRecord build() {
            EntryRecord record = new EntryRecord();
            record.setEntryId(entryId);
            record.setVehicleId(vehicleId);
            record.setSpaceId(spaceId);
            record.setParkingId(parkingId);
            record.setVehicleNumber(vehicleNumber);
            record.setSpaceNumber(spaceNumber);
            record.setEntryTime(entryTime);
            record.setEntryStatus(entryStatus);
            return record;
        }

        public EntryRecord buildActiveEntry(Vehicle vehicle, ParkingSpace space, ParkingLot lot) {
            return entryRecordBuilder()
                    .vehicleId(vehicle.getVehicleId())
                    .spaceId(space.getSpaceId())
                    .parkingId(lot.getParkingId())
                    .vehicleNumber(vehicle.getVehicleNumber())
                    .spaceNumber(space.getSpaceNumber())
                    .entryStatus("parked")
                    .build();
        }

        public EntryRecord buildExitedEntry(Vehicle vehicle, ParkingSpace space, ParkingLot lot) {
            return entryRecordBuilder()
                    .vehicleId(vehicle.getVehicleId())
                    .spaceId(space.getSpaceId())
                    .parkingId(lot.getParkingId())
                    .vehicleNumber(vehicle.getVehicleNumber())
                    .spaceNumber(space.getSpaceNumber())
                    .entryTime(LocalDateTime.now().minusHours(2))
                    .entryStatus("exited")
                    .build();
        }
    }

    public static class ExitRecordBuilder {
        private String exitId = generateId("exit");
        private String entryId = generateId("entry");
        private String vehicleId = generateId("vehicle");
        private String spaceId = generateId("space");
        private LocalDateTime exitTime = LocalDateTime.now();
        private int parkingDuration = 120;
        private double parkingFee = 20.0;
        private String exitStatus = "completed";

        public ExitRecordBuilder exitId(String exitId) {
            this.exitId = exitId;
            return this;
        }

        public ExitRecordBuilder entryId(String entryId) {
            this.entryId = entryId;
            return this;
        }

        public ExitRecordBuilder vehicleId(String vehicleId) {
            this.vehicleId = vehicleId;
            return this;
        }

        public ExitRecordBuilder spaceId(String spaceId) {
            this.spaceId = spaceId;
            return this;
        }

        public ExitRecordBuilder exitTime(LocalDateTime exitTime) {
            this.exitTime = exitTime;
            return this;
        }

        public ExitRecordBuilder parkingDuration(int parkingDuration) {
            this.parkingDuration = parkingDuration;
            return this;
        }

        public ExitRecordBuilder parkingFee(double parkingFee) {
            this.parkingFee = parkingFee;
            return this;
        }

        public ExitRecordBuilder exitStatus(String exitStatus) {
            this.exitStatus = exitStatus;
            return this;
        }

        public ExitRecord build() {
            ExitRecord record = new ExitRecord();
            record.setExitId(exitId);
            record.setEntryId(entryId);
            record.setVehicleId(vehicleId);
            record.setSpaceId(spaceId);
            record.setExitTime(exitTime);
            record.setParkingDuration(parkingDuration);
            record.setParkingFee(parkingFee);
            record.setExitStatus(exitStatus);
            return record;
        }

        public ExitRecord buildShortParking() {
            return exitRecordBuilder()
                    .parkingDuration(30)
                    .parkingFee(10.0)
                    .build();
        }

        public ExitRecord buildLongParking() {
            return exitRecordBuilder()
                    .parkingDuration(480)
                    .parkingFee(80.0)
                    .build();
        }
    }

    public static class SettlementRecordBuilder {
        private String settlementId = generateId("settlement");
        private String entryId = generateId("entry");
        private String exitId = generateId("exit");
        private String vehicleId = generateId("vehicle");
        private double parkingFee = 20.0;
        private String paymentMethod = "wechat";
        private String paymentStatus = "pending";
        private LocalDateTime settlementTime = null;

        public SettlementRecordBuilder settlementId(String settlementId) {
            this.settlementId = settlementId;
            return this;
        }

        public SettlementRecordBuilder entryId(String entryId) {
            this.entryId = entryId;
            return this;
        }

        public SettlementRecordBuilder exitId(String exitId) {
            this.exitId = exitId;
            return this;
        }

        public SettlementRecordBuilder vehicleId(String vehicleId) {
            this.vehicleId = vehicleId;
            return this;
        }

        public SettlementRecordBuilder parkingFee(double parkingFee) {
            this.parkingFee = parkingFee;
            return this;
        }

        public SettlementRecordBuilder paymentMethod(String paymentMethod) {
            this.paymentMethod = paymentMethod;
            return this;
        }

        public SettlementRecordBuilder paymentStatus(String paymentStatus) {
            this.paymentStatus = paymentStatus;
            return this;
        }

        public SettlementRecordBuilder settlementTime(LocalDateTime settlementTime) {
            this.settlementTime = settlementTime;
            return this;
        }

        public SettlementRecord build() {
            SettlementRecord record = new SettlementRecord();
            record.setSettlementId(settlementId);
            record.setEntryId(entryId);
            record.setExitId(exitId);
            record.setVehicleId(vehicleId);
            record.setParkingFee(parkingFee);
            record.setPaymentMethod(paymentMethod);
            record.setPaymentStatus(paymentStatus);
            record.setSettlementTime(settlementTime);
            return record;
        }

        public SettlementRecord buildPendingSettlement() {
            return settlementRecordBuilder()
                    .paymentStatus("pending")
                    .settlementTime(null)
                    .build();
        }

        public SettlementRecord buildPaidSettlement() {
            return settlementRecordBuilder()
                    .paymentStatus("paid")
                    .settlementTime(LocalDateTime.now())
                    .build();
        }

        public SettlementRecord buildFailedSettlement() {
            return settlementRecordBuilder()
                    .paymentStatus("failed")
                    .paymentMethod("alipay")
                    .build();
        }
    }

    public static class ReservationRecordBuilder {
        private String reserveId = generateId("reserve");
        private String spaceId = generateId("space");
        private String vehicleId = generateId("vehicle");
        private String parkingId = generateId("parking");
        private String vehicleNumber = "京A12345";
        private String spaceNumber = "A001";
        private LocalDateTime reserveTime = LocalDateTime.now();
        private LocalDateTime expectedStartTime = LocalDateTime.now().plusHours(1);
        private LocalDateTime expectedEndTime = LocalDateTime.now().plusHours(3);
        private String reserveStatus = "confirmed";

        public ReservationRecordBuilder reserveId(String reserveId) {
            this.reserveId = reserveId;
            return this;
        }

        public ReservationRecordBuilder spaceId(String spaceId) {
            this.spaceId = spaceId;
            return this;
        }

        public ReservationRecordBuilder vehicleId(String vehicleId) {
            this.vehicleId = vehicleId;
            return this;
        }

        public ReservationRecordBuilder parkingId(String parkingId) {
            this.parkingId = parkingId;
            return this;
        }

        public ReservationRecordBuilder vehicleNumber(String vehicleNumber) {
            this.vehicleNumber = vehicleNumber;
            return this;
        }

        public ReservationRecordBuilder spaceNumber(String spaceNumber) {
            this.spaceNumber = spaceNumber;
            return this;
        }

        public ReservationRecordBuilder reserveTime(LocalDateTime reserveTime) {
            this.reserveTime = reserveTime;
            return this;
        }

        public ReservationRecordBuilder expectedStartTime(LocalDateTime expectedStartTime) {
            this.expectedStartTime = expectedStartTime;
            return this;
        }

        public ReservationRecordBuilder expectedEndTime(LocalDateTime expectedEndTime) {
            this.expectedEndTime = expectedEndTime;
            return this;
        }

        public ReservationRecordBuilder reserveStatus(String reserveStatus) {
            this.reserveStatus = reserveStatus;
            return this;
        }

        public ReservationRecord build() {
            ReservationRecord record = new ReservationRecord();
            record.setReserveId(reserveId);
            record.setSpaceId(spaceId);
            record.setVehicleId(vehicleId);
            record.setParkingId(parkingId);
            record.setVehicleNumber(vehicleNumber);
            record.setSpaceNumber(spaceNumber);
            record.setReserveTime(reserveTime);
            record.setExpectedStartTime(expectedStartTime);
            record.setExpectedEndTime(expectedEndTime);
            record.setReserveStatus(reserveStatus);
            return record;
        }

        public ReservationRecord buildConfirmedReservation(Vehicle vehicle, ParkingSpace space, ParkingLot lot) {
            return reservationRecordBuilder()
                    .vehicleId(vehicle.getVehicleId())
                    .spaceId(space.getSpaceId())
                    .parkingId(lot.getParkingId())
                    .vehicleNumber(vehicle.getVehicleNumber())
                    .spaceNumber(space.getSpaceNumber())
                    .reserveStatus("confirmed")
                    .build();
        }
    }

    public static class EntryRequestBuilder {
        private String vehicleNumber = "京A12345";
        private String parkingId = generateId("parking");
        private String vehicleType = "sedan";
        private String vehicleOwner = "张三";
        private String vehiclePhone = "13800138000";

        public EntryRequestBuilder vehicleNumber(String vehicleNumber) {
            this.vehicleNumber = vehicleNumber;
            return this;
        }

        public EntryRequestBuilder parkingId(String parkingId) {
            this.parkingId = parkingId;
            return this;
        }

        public EntryRequestBuilder vehicleType(String vehicleType) {
            this.vehicleType = vehicleType;
            return this;
        }

        public EntryRequestBuilder vehicleOwner(String vehicleOwner) {
            this.vehicleOwner = vehicleOwner;
            return this;
        }

        public EntryRequestBuilder vehiclePhone(String vehiclePhone) {
            this.vehiclePhone = vehiclePhone;
            return this;
        }

        public EntryRequest build() {
            EntryRequest request = new EntryRequest();
            request.setVehicleNumber(vehicleNumber);
            request.setParkingId(parkingId);
            request.setVehicleType(vehicleType);
            request.setVehicleOwner(vehicleOwner);
            request.setVehiclePhone(vehiclePhone);
            return request;
        }

        public EntryRequest buildStandardRequest(String parkingId) {
            return entryRequestBuilder()
                    .parkingId(parkingId)
                    .vehicleNumber("京A12345")
                    .vehicleType("sedan")
                    .build();
        }

        public EntryRequest buildVipRequest(String parkingId) {
            return entryRequestBuilder()
                    .parkingId(parkingId)
                    .vehicleNumber("京V88888")
                    .vehicleType("vip")
                    .vehicleOwner("VIP客户")
                    .build();
        }
    }

    public static class ExitRequestBuilder {
        private String entryId = generateId("entry");

        public ExitRequestBuilder entryId(String entryId) {
            this.entryId = entryId;
            return this;
        }

        public ExitRequest build() {
            ExitRequest request = new ExitRequest();
            request.setEntryId(entryId);
            return request;
        }
    }

    public static class PaymentRequestBuilder {
        private String settlementId = generateId("settlement");
        private String paymentMethod = "wechat";

        public PaymentRequestBuilder settlementId(String settlementId) {
            this.settlementId = settlementId;
            return this;
        }

        public PaymentRequestBuilder paymentMethod(String paymentMethod) {
            this.paymentMethod = paymentMethod;
            return this;
        }

        public PaymentRequest build() {
            PaymentRequest request = new PaymentRequest();
            request.setSettlementId(settlementId);
            request.setPaymentMethod(paymentMethod);
            return request;
        }

        public PaymentRequest buildWechatPayment(String settlementId) {
            return paymentRequestBuilder()
                    .settlementId(settlementId)
                    .paymentMethod("wechat")
                    .build();
        }

        public PaymentRequest buildAlipayPayment(String settlementId) {
            return paymentRequestBuilder()
                    .settlementId(settlementId)
                    .paymentMethod("alipay")
                    .build();
        }
    }
}
