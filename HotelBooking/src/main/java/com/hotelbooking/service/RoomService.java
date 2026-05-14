package com.hotelbooking.service;

import com.hotelbooking.config.RoomTypeConfig;
import com.hotelbooking.config.RoomTypeConfig.RoomTypeConfigEntry;
import com.hotelbooking.model.Booking;
import com.hotelbooking.model.Hotel;
import com.hotelbooking.model.Room;
import com.hotelbooking.repository.BookingRepository;
import com.hotelbooking.repository.HotelRepository;
import com.hotelbooking.repository.RoomRepository;
import com.hotelbooking.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class RoomService {
    private static final Logger logger = LoggerFactory.getLogger(RoomService.class);

    private final RoomRepository roomRepository;
    private final HotelRepository hotelRepository;
    private final BookingRepository bookingRepository;
    private final RoomTypeConfig roomTypeConfig;

    public RoomService(RoomRepository roomRepository, HotelRepository hotelRepository,
                       BookingRepository bookingRepository, RoomTypeConfig roomTypeConfig) {
        this.roomRepository = roomRepository;
        this.hotelRepository = hotelRepository;
        this.bookingRepository = bookingRepository;
        this.roomTypeConfig = roomTypeConfig;
    }

    @Transactional
    public Room createRoom(Room room, String hotelId) {
        Hotel hotel = hotelRepository.findById(hotelId)
                .orElseThrow(() -> new RuntimeException("酒店不存在: " + hotelId));
        
        String roomType = room.getRoomType();
        if (roomType != null && !roomType.isEmpty()) {
            if (!roomTypeConfig.isValidRoomType(roomType)) {
                logger.warn("房间类型未配置，使用默认类型: {}", roomType);
                room.setRoomType(roomTypeConfig.getDefaultType());
            }
        } else {
            room.setRoomType(roomTypeConfig.getDefaultType());
        }

        RoomTypeConfigEntry typeConfig = roomTypeConfig.getTypeConfig(room.getRoomType());
        if (typeConfig != null) {
            if (room.getRoomPrice() == null || room.getRoomPrice() <= 0) {
                room.setRoomPrice(typeConfig.getBasePrice());
            }
            if (room.getRoomFeatures() == null || room.getRoomFeatures().isEmpty()) {
                room.setRoomFeatures(typeConfig.getDefaultFeatures());
            }
        }

        room.setRoomId(IdGenerator.generateRoomId());
        room.setHotel(hotel);
        room.setHotelId(hotelId);
        room.setCreatedAt(LocalDateTime.now());
        if (room.getRoomStatus() == null) {
            room.setRoomStatus("available");
        }
        
        Room saved = roomRepository.save(room);
        logger.info("房间创建成功: roomId={}, type={}, price={}", 
                saved.getRoomId(), saved.getRoomType(), saved.getRoomPrice());
        return saved;
    }

    @Transactional
    public Room updateRoomStatus(String roomId, String status) {
        Room room = roomRepository.findByIdForUpdate(roomId)
                .orElseThrow(() -> new RuntimeException("房间不存在: " + roomId));
        
        String previousStatus = room.getRoomStatus();
        room.setRoomStatus(status);
        Room updated = roomRepository.save(room);
        logger.info("房间状态更新: roomId={}, {} -> {}", roomId, previousStatus, status);
        return updated;
    }

    public Optional<Room> getRoomById(String roomId) {
        return roomRepository.findById(roomId);
    }

    public List<Room> getRoomsByHotel(String hotelId) {
        return roomRepository.findByHotelId(hotelId);
    }

    public List<Room> getAvailableRooms(String hotelId) {
        return roomRepository.findAvailableRoomsByHotelId(hotelId);
    }

    public List<Room> searchAvailableRooms(String hotelId, LocalDate checkInDate, LocalDate checkOutDate) {
        Hotel hotel = hotelRepository.findById(hotelId)
                .orElseThrow(() -> new RuntimeException("酒店不存在: " + hotelId));
        
        if (!"active".equals(hotel.getHotelStatus())) {
            throw new RuntimeException("酒店已关闭");
        }

        List<Room> allRooms = roomRepository.findByHotelId(hotelId);
        
        return allRooms.stream()
                .filter(room -> isRoomAvailable(room.getRoomId(), checkInDate, checkOutDate))
                .collect(Collectors.toList());
    }

    public boolean isRoomAvailable(String roomId, LocalDate checkInDate, LocalDate checkOutDate) {
        if (checkInDate.isAfter(checkOutDate) || checkInDate.isEqual(checkOutDate)) {
            return false;
        }
        
        var conflicts = bookingRepository.findConflictingBookings(roomId, checkInDate, checkOutDate);
        return conflicts.isEmpty();
    }

    @Transactional
    public Room updateRoom(String roomId, Room roomDetails) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new RuntimeException("房间不存在: " + roomId));
        
        if (roomDetails.getRoomNumber() != null) {
            room.setRoomNumber(roomDetails.getRoomNumber());
        }
        if (roomDetails.getRoomType() != null) {
            if (roomTypeConfig.isValidRoomType(roomDetails.getRoomType())) {
                room.setRoomType(roomDetails.getRoomType());
                RoomTypeConfigEntry typeConfig = roomTypeConfig.getTypeConfig(roomDetails.getRoomType());
                if (typeConfig != null && roomDetails.getRoomPrice() == null) {
                    room.setRoomPrice(typeConfig.getBasePrice());
                }
            } else {
                logger.warn("无效的房间类型: {}，保持原值", roomDetails.getRoomType());
            }
        }
        if (roomDetails.getRoomPrice() != null) {
            room.setRoomPrice(roomDetails.getRoomPrice());
        }
        if (roomDetails.getRoomStatus() != null) {
            room.setRoomStatus(roomDetails.getRoomStatus());
        }
        if (roomDetails.getRoomFeatures() != null && !roomDetails.getRoomFeatures().isEmpty()) {
            room.setRoomFeatures(roomDetails.getRoomFeatures());
        }
        
        Room updated = roomRepository.save(room);
        logger.info("房间信息更新: roomId={}", roomId);
        return updated;
    }

    public List<Room> getRoomsByType(String hotelId, String roomType) {
        if (!roomTypeConfig.isValidRoomType(roomType)) {
            logger.warn("请求未配置的房间类型: {}", roomType);
        }
        return roomRepository.findByHotelIdAndRoomType(hotelId, roomType);
    }

    public List<String> getAllRoomTypes() {
        return roomTypeConfig.getAllRoomTypes();
    }

    public RoomTypeConfigEntry getRoomTypeConfig(String roomType) {
        return roomTypeConfig.getTypeConfig(roomType);
    }

    public String getDefaultRoomType() {
        return roomTypeConfig.getDefaultType();
    }

    public boolean isValidRoomType(String roomType) {
        return roomTypeConfig.isValidRoomType(roomType);
    }

    public Double calculateRoomPrice(String roomType, int days) {
        RoomTypeConfigEntry config = roomTypeConfig.getTypeConfig(roomType);
        if (config == null) {
            return null;
        }
        return config.getActualPrice() * days;
    }
}
