package com.ceos24.springboot.reservation.repository;

import com.ceos24.springboot.reservation.domain.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import java.util.List;

import java.util.Optional;

public interface ReservationRepository
        extends JpaRepository<Reservation, Long> {

    Optional<Reservation> findByReservationIdAndUser_Id(
            Long reservationId,
            Long userId
    );

    // 최근 예메부터 정렬하여 로그인 ID와 예매자의 ID 일치하는 예매만 조회
    @EntityGraph(attributePaths = "screening")
    List<Reservation> findAllByUser_IdOrderByReservationAtDesc(
            Long userId
    );
}