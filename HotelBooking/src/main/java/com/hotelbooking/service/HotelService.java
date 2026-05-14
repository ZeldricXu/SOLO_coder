package com.hotelbooking.service;

import com.hotelbooking.model.Hotel;
import com.hotelbooking.repository.HotelRepository;
import com.hotelbooking.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class HotelService {
    private static final Logger logger = LoggerFactory.getLogger(HotelService.class);

    private final HotelRepository hotelRepository;

    public HotelService(HotelRepository hotelRepository) {
        this.hotelRepository = hotelRepository;
    }

    @Transactional
    public Hotel createHotel(Hotel hotel) {
        hotel.setHotelId(IdGenerator.generateHotelId());
        hotel.setCreatedAt(LocalDateTime.now());
        if (hotel.getHotelStatus() == null) {
            hotel.setHotelStatus("active");
        }
        Hotel saved = hotelRepository.save(hotel);
        logger.info("酒店创建成功: {}", saved.getHotelId());
        return saved;
    }

    @Transactional
    public Hotel updateHotel(String hotelId, Hotel hotelDetails) {
        Hotel hotel = hotelRepository.findById(hotelId)
                .orElseThrow(() -> new RuntimeException("酒店不存在: " + hotelId));
        
        if (hotelDetails.getHotelName() != null) {
            hotel.setHotelName(hotelDetails.getHotelName());
        }
        if (hotelDetails.getHotelType() != null) {
            hotel.setHotelType(hotelDetails.getHotelType());
        }
        if (hotelDetails.getHotelAddress() != null) {
            hotel.setHotelAddress(hotelDetails.getHotelAddress());
        }
        if (hotelDetails.getHotelRating() != null) {
            hotel.setHotelRating(hotelDetails.getHotelRating());
        }
        if (hotelDetails.getHotelRooms() != null) {
            hotel.setHotelRooms(hotelDetails.getHotelRooms());
        }
        if (hotelDetails.getHotelStatus() != null) {
            hotel.setHotelStatus(hotelDetails.getHotelStatus());
        }
        
        Hotel updated = hotelRepository.save(hotel);
        logger.info("酒店更新成功: {}", hotelId);
        return updated;
    }

    public Optional<Hotel> getHotelById(String hotelId) {
        return hotelRepository.findById(hotelId);
    }

    public List<Hotel> getAllHotels() {
        return hotelRepository.findAll();
    }

    public List<Hotel> getActiveHotels() {
        return hotelRepository.findByHotelStatus("active");
    }

    @Transactional
    public void deleteHotel(String hotelId) {
        Hotel hotel = hotelRepository.findById(hotelId)
                .orElseThrow(() -> new RuntimeException("酒店不存在: " + hotelId));
        hotel.setHotelStatus("inactive");
        hotelRepository.save(hotel);
        logger.info("酒店已停用: {}", hotelId);
    }

    public boolean isHotelActive(String hotelId) {
        return hotelRepository.findById(hotelId)
                .map(h -> "active".equals(h.getHotelStatus()))
                .orElse(false);
    }
}
