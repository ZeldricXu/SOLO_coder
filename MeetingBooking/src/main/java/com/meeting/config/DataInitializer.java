package com.meeting.config;

import com.meeting.entity.Device;
import com.meeting.entity.MeetingRoom;
import com.meeting.entity.MeetingType;
import com.meeting.repository.DeviceRepository;
import com.meeting.repository.MeetingRoomRepository;
import com.meeting.repository.MeetingTypeRepository;
import com.meeting.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final MeetingRoomRepository roomRepository;
    private final MeetingTypeRepository typeRepository;
    private final DeviceRepository deviceRepository;

    @Override
    public void run(String... args) {
        initializeMeetingTypes();
        initializeMeetingRooms();
        initializeDevices();
    }

    private void initializeMeetingTypes() {
        if (!typeRepository.existsByTypeCode("regular")) {
            MeetingType regularType = MeetingType.builder()
                    .typeId(IdGenerator.generateTypeId())
                    .typeCode("regular")
                    .typeName("日常会议")
                    .description("常规的日常工作会议")
                    .defaultDurationMinutes(60)
                    .requiredApproval(false)
                    .status("active")
                    .build();
            typeRepository.save(regularType);
            log.info("初始化会议类型: regular");
        }

        if (!typeRepository.existsByTypeCode("urgent")) {
            MeetingType urgentType = MeetingType.builder()
                    .typeId(IdGenerator.generateTypeId())
                    .typeCode("urgent")
                    .typeName("紧急会议")
                    .description("需要立即召开的紧急会议")
                    .defaultDurationMinutes(30)
                    .requiredApproval(false)
                    .status("active")
                    .build();
            typeRepository.save(urgentType);
            log.info("初始化会议类型: urgent");
        }

        if (!typeRepository.existsByTypeCode("training")) {
            MeetingType trainingType = MeetingType.builder()
                    .typeId(IdGenerator.generateTypeId())
                    .typeCode("training")
                    .typeName("培训会议")
                    .description("培训和学习会议")
                    .defaultDurationMinutes(120)
                    .requiredApproval(false)
                    .status("active")
                    .build();
            typeRepository.save(trainingType);
            log.info("初始化会议类型: training");
        }

        if (!typeRepository.existsByTypeCode("review")) {
            MeetingType reviewType = MeetingType.builder()
                    .typeId(IdGenerator.generateTypeId())
                    .typeCode("review")
                    .typeName("评审会议")
                    .description("项目评审和代码审查会议")
                    .defaultDurationMinutes(90)
                    .requiredApproval(true)
                    .status("active")
                    .build();
            typeRepository.save(reviewType);
            log.info("初始化会议类型: review");
        }

        if (!typeRepository.existsByTypeCode("interview")) {
            MeetingType interviewType = MeetingType.builder()
                    .typeId(IdGenerator.generateTypeId())
                    .typeCode("interview")
                    .typeName("面试会议")
                    .description("招聘面试会议")
                    .defaultDurationMinutes(45)
                    .requiredApproval(true)
                    .status("active")
                    .build();
            typeRepository.save(interviewType);
            log.info("初始化会议类型: interview");
        }
    }

    private void initializeMeetingRooms() {
        if (roomRepository.count() == 0) {
            MeetingRoom room1 = MeetingRoom.builder()
                    .roomId("room_001")
                    .roomName("创新会议室")
                    .roomCapacity(10)
                    .roomLocation("1号楼1层101室")
                    .roomStatus("available")
                    .roomFeatures(Arrays.asList("投影", "白板", "视频会议"))
                    .build();
            roomRepository.save(room1);
            log.info("初始化会议室: 创新会议室");

            MeetingRoom room2 = MeetingRoom.builder()
                    .roomId("room_002")
                    .roomName("阳光会议室")
                    .roomCapacity(20)
                    .roomLocation("1号楼2层201室")
                    .roomStatus("available")
                    .roomFeatures(Arrays.asList("投影", "白板", "音响系统", "视频会议"))
                    .build();
            roomRepository.save(room2);
            log.info("初始化会议室: 阳光会议室");

            MeetingRoom room3 = MeetingRoom.builder()
                    .roomId("room_003")
                    .roomName("星空会议室")
                    .roomCapacity(8)
                    .roomLocation("1号楼2层202室")
                    .roomStatus("available")
                    .roomFeatures(Arrays.asList("投影", "白板"))
                    .build();
            roomRepository.save(room3);
            log.info("初始化会议室: 星空会议室");

            MeetingRoom room4 = MeetingRoom.builder()
                    .roomId("room_004")
                    .roomName("绿洲会议室")
                    .roomCapacity(50)
                    .roomLocation("2号楼1层101室")
                    .roomStatus("available")
                    .roomFeatures(Arrays.asList("投影", "白板", "音响系统", "视频会议", "舞台"))
                    .build();
            roomRepository.save(room4);
            log.info("初始化会议室: 绿洲会议室");

            MeetingRoom room5 = MeetingRoom.builder()
                    .roomId("room_005")
                    .roomName("翠竹会议室")
                    .roomCapacity(6)
                    .roomLocation("2号楼2层201室")
                    .roomStatus("available")
                    .roomFeatures(Arrays.asList("白板"))
                    .build();
            roomRepository.save(room5);
            log.info("初始化会议室: 翠竹会议室");
        }
    }

    private void initializeDevices() {
        if (deviceRepository.count() == 0) {
            Device device1 = Device.builder()
                    .deviceId("device_001")
                    .roomId("room_001")
                    .deviceType("projector")
                    .deviceName("EPSON投影仪")
                    .deviceStatus("available")
                    .deviceFeatures("4K高清投影")
                    .build();
            deviceRepository.save(device1);

            Device device2 = Device.builder()
                    .deviceId("device_002")
                    .roomId("room_001")
                    .deviceType("whiteboard")
                    .deviceName("电子白板")
                    .deviceStatus("available")
                    .deviceFeatures("交互式智能白板")
                    .build();
            deviceRepository.save(device2);

            Device device3 = Device.builder()
                    .deviceId("device_003")
                    .roomId("room_002")
                    .deviceType("projector")
                    .deviceName("SONY投影仪")
                    .deviceStatus("available")
                    .deviceFeatures("激光高清投影")
                    .build();
            deviceRepository.save(device3);

            Device device4 = Device.builder()
                    .deviceId("device_004")
                    .roomId("room_002")
                    .deviceType("speaker")
                    .deviceName("JBL音响")
                    .deviceStatus("available")
                    .deviceFeatures("立体声音响系统")
                    .build();
            deviceRepository.save(device4);

            Device device5 = Device.builder()
                    .deviceId("device_005")
                    .roomId("room_004")
                    .deviceType("projector")
                    .deviceName("PANASONIC激光投影")
                    .deviceStatus("available")
                    .deviceFeatures("4K激光超短焦投影")
                    .build();
            deviceRepository.save(device5);

            Device device6 = Device.builder()
                    .deviceId("device_006")
                    .roomId("room_004")
                    .deviceType("speaker")
                    .deviceName("BOSE音响系统")
                    .deviceStatus("available")
                    .deviceFeatures("专业环绕音响")
                    .build();
            deviceRepository.save(device6);

            log.info("初始化设备: 6个设备");
        }
    }
}
