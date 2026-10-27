package com.ceos24.springboot.reservation;

import com.ceos24.springboot.auth.jwt.JwtProvider;
import com.ceos24.springboot.global.exception.ErrorCode;
import com.ceos24.springboot.global.exception.GlobalException;
import com.ceos24.springboot.movie.domain.AgeRating;
import com.ceos24.springboot.movie.domain.Movie;
import com.ceos24.springboot.reservation.domain.Reservation;
import com.ceos24.springboot.reservation.domain.ReservationStatus;
import com.ceos24.springboot.reservation.dto.ReservationCreateRequest;
import com.ceos24.springboot.reservation.dto.ReservationResponse;
import com.ceos24.springboot.reservation.repository.ReservationRepository;
import com.ceos24.springboot.reservation.repository.ReservationSeatRepository;
import com.ceos24.springboot.reservation.service.ReservationExpirationScheduler;
import com.ceos24.springboot.reservation.service.ReservationExpirationService;
import com.ceos24.springboot.reservation.service.ReservationService;
import com.ceos24.springboot.theater.domain.*;
import com.ceos24.springboot.user.domain.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReservationHoldIntegrationTest {
    @Autowired private ReservationService service;
    @Autowired private ReservationExpirationService expirationService;
    @Autowired private ReservationExpirationScheduler scheduler;
    @Autowired private ReservationRepository reservations;
    @Autowired private ReservationSeatRepository reservationSeats;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;

    private TransactionTemplate transaction;
    private Long userId;
    private Long otherUserId;
    private Long screeningId;
    private Long otherScreeningId;
    private Long firstSeatId;
    private Long secondSeatId;
    private Long screenId;
    private Long theaterId;
    private Long movieId;
    private Long priceId;

    @BeforeEach
    void setUp() {
        transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(ignored -> {
            String suffix = UUID.randomUUID().toString();
            userId = persist(User.create("선택자", suffix + "@hold.test", "unused", "010-1111-1111")).getId();
            otherUserId = persist(User.create("다른선택자", suffix + "@other.test", "unused", "010-2222-2222")).getId();
            Theater theater = persist(Theater.create("점유테스트", Region.Seoul));
            theaterId = theater.getTheaterId();
            Screen screen = persist(Screen.create(theater, "1관", ScreenType.General, 1, 2));
            screenId = screen.getScreenId();
            Movie movie = persist(Movie.builder().titleKr("점유테스트")
                    .runtimeMinutes(120).ageRating(AgeRating.ALL).build());
            movieId = movie.getMovieId();
            LocalDate date = LocalDate.now().plusDays(7);
            screeningId = persist(Screening.builder().screen(screen).movie(movie)
                    .screeningDate(date).startTime(LocalTime.NOON)
                    .endTime(LocalTime.of(14, 0)).screeningType(ScreeningType.Original)
                    .build()).getScreeningId();
            otherScreeningId = persist(Screening.builder().screen(screen).movie(movie)
                    .screeningDate(date).startTime(LocalTime.of(15, 0))
                    .endTime(LocalTime.of(17, 0)).screeningType(ScreeningType.Original)
                    .build()).getScreeningId();
            firstSeatId = persist(Seat.create(screen, "A", 1)).getSeatId();
            secondSeatId = persist(Seat.create(screen, "A", 2)).getSeatId();
            jdbc.update("""
                    insert into price (audience_type, day_type, time_type, screen_category, ticket_price)
                    values ('Adult', ?, 'Normal', 'General', 12345)
                    """, date.getDayOfWeek().getValue() >= 6 ? "Weekend" : "Weekday");
            priceId = jdbc.queryForObject("select price_id from price where ticket_price = 12345", Long.class);
        });
    }

    @AfterEach
    void cleanUp() {
        transaction.executeWithoutResult(ignored -> {
            jdbc.update("delete from reservation_seat where screening_id in (?, ?)", screeningId, otherScreeningId);
            jdbc.update("delete from reservation where screening_id in (?, ?)", screeningId, otherScreeningId);
            jdbc.update("delete from screening where screening_id in (?, ?)", screeningId, otherScreeningId);
            jdbc.update("delete from seat where screen_id = ?", screenId);
            jdbc.update("delete from screen where screen_id = ?", screenId);
            jdbc.update("delete from theater where theater_id = ?", theaterId);
            jdbc.update("delete from movie where movie_id = ?", movieId);
            jdbc.update("delete from cgv_user where user_id in (?, ?)", userId, otherUserId);
            jdbc.update("delete from price where price_id = ?", priceId);
        });
    }

    @Test
    void selectionCreatesPendingReservationForExactlyTenMinutes() {
        ReservationResponse response = create(userId, screeningId, firstSeatId);
        assertThat(response.status()).isEqualTo(ReservationStatus.Pending);
        assertThat(Duration.between(response.reservationAt(), response.expiresAt())).isEqualTo(Duration.ofMinutes(10));
        assertThat(reservationSeats.findAllByReservation_ReservationId(response.reservationId())).hasSize(1);
    }

    @Test
    void occupiedSeatConflictsButDifferentSeatAndDifferentScreeningAreAllowed() {
        create(userId, screeningId, firstSeatId);
        assertConflict(() -> create(otherUserId, screeningId, firstSeatId), ErrorCode.SEAT_UNAVAILABLE);
        assertThat(create(otherUserId, screeningId, secondSeatId).status()).isEqualTo(ReservationStatus.Pending);
        assertThat(create(otherUserId, otherScreeningId, firstSeatId).status()).isEqualTo(ReservationStatus.Pending);
    }

    @Test
    void failedMultiSeatSelectionDoesNotOccupyTheOtherSeat() {
        create(userId, screeningId, secondSeatId);
        assertConflict(() -> create(otherUserId, screeningId, firstSeatId, secondSeatId), ErrorCode.SEAT_UNAVAILABLE);
        assertThat(create(otherUserId, screeningId, firstSeatId).status()).isEqualTo(ReservationStatus.Pending);
    }

    @Test
    void confirmEndpointIsOwnedAndIdempotentAndKeepsSeatsOccupied() throws Exception {
        ReservationResponse pending = create(userId, screeningId, firstSeatId);
        mockMvc.perform(patch("/api/reservations/{id}/confirm", pending.reservationId())
                        .header("Authorization", "Bearer " + jwtProvider.createAccessToken(otherUserId)))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/reservations/{id}/confirm", pending.reservationId()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/reservations/{id}/confirm", pending.reservationId())
                        .header("Authorization", "Bearer " + jwtProvider.createAccessToken(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Reserved"));
        assertThat(service.confirmReservation(userId, pending.reservationId()).status()).isEqualTo(ReservationStatus.Reserved);
        backdateExpiry(pending.reservationId());
        expirationService.expireReservation(pending.reservationId());
        assertThat(savedStatus(pending.reservationId())).isEqualTo(ReservationStatus.Reserved);
        assertConflict(() -> create(otherUserId, screeningId, firstSeatId), ErrorCode.SEAT_UNAVAILABLE);
    }

    @Test
    void cancellationReturnsCancledAndReleasesSeatsWithoutCancellationHistory() throws Exception {
        ReservationResponse pending = create(userId, screeningId, firstSeatId);
        mockMvc.perform(patch("/api/reservations/{id}/cancel", pending.reservationId())
                        .header("Authorization", "Bearer " + jwtProvider.createAccessToken(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancled"));
        assertThat(savedStatus(pending.reservationId())).isEqualTo(ReservationStatus.Canceled);
        assertThat(reservationSeats.findAllByReservation_ReservationId(pending.reservationId())).isEmpty();
        assertConflict(() -> service.confirmReservation(userId, pending.reservationId()), ErrorCode.INVALID_RESERVATION_STATUS);
        assertThat(create(otherUserId, screeningId, firstSeatId).status()).isEqualTo(ReservationStatus.Pending);
    }

    @Test
    void expiredConfirmationCommitsExpirationAndSeatReleaseDespiteConflictResponse() throws Exception {
        ReservationResponse pending = create(userId, screeningId, firstSeatId);
        backdateExpiry(pending.reservationId());
        mockMvc.perform(patch("/api/reservations/{id}/confirm", pending.reservationId())
                        .header("Authorization", "Bearer " + jwtProvider.createAccessToken(userId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_EXPIRED"));
        assertThat(savedStatus(pending.reservationId())).isEqualTo(ReservationStatus.Expired);
        assertThat(reservationSeats.findAllByReservation_ReservationId(pending.reservationId())).isEmpty();
        create(otherUserId, screeningId, firstSeatId);
    }

    @Test
    void newSelectionReleasesWholeExpiredHoldWithoutWaitingForScheduler() {
        ReservationResponse pending = create(userId, screeningId, firstSeatId, secondSeatId);
        backdateExpiry(pending.reservationId());
        create(otherUserId, screeningId, firstSeatId);
        assertThat(savedStatus(pending.reservationId())).isEqualTo(ReservationStatus.Expired);
        assertThat(reservationSeats.findAllByReservation_ReservationId(pending.reservationId())).isEmpty();
        create(otherUserId, screeningId, secondSeatId);
        expirationService.expireReservation(pending.reservationId());
        assertConflict(() -> create(userId, screeningId, firstSeatId), ErrorCode.SEAT_UNAVAILABLE);
    }

    @Test
    void scheduledCleanupExpiresOnlyPendingReservations() {
        ReservationResponse pending = create(userId, screeningId, firstSeatId);
        ReservationResponse confirmed = create(userId, screeningId, secondSeatId);
        service.confirmReservation(userId, confirmed.reservationId());
        backdateExpiry(pending.reservationId());
        backdateExpiry(confirmed.reservationId());
        scheduler.expirePendingReservations();
        assertThat(savedStatus(pending.reservationId())).isEqualTo(ReservationStatus.Expired);
        assertThat(savedStatus(confirmed.reservationId())).isEqualTo(ReservationStatus.Reserved);
        assertThat(reservationSeats.findAllByReservation_ReservationId(pending.reservationId())).isEmpty();
        assertThat(reservationSeats.findAllByReservation_ReservationId(confirmed.reservationId())).hasSize(1);
    }

    @Test
    void readingAnExpiredHoldReturnsExpiredAndReleasesSeats() {
        ReservationResponse pending = create(userId, screeningId, firstSeatId);
        backdateExpiry(pending.reservationId());
        assertThat(service.getReservation(userId, pending.reservationId()).status()).isEqualTo(ReservationStatus.Expired);
        assertThat(service.getMyReservations(userId).getFirst().seats()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"Pending", "Reserved"})
    void canceledReservationCanBeRebookedAndConfirmedByAnotherUser(ReservationStatus initialStatus) {
        ReservationResponse original = create(userId, screeningId, firstSeatId, secondSeatId);
        if (initialStatus == ReservationStatus.Reserved) {
            service.confirmReservation(userId, original.reservationId());
        }

        service.cancelReservation(userId, original.reservationId());

        assertThat(savedStatus(original.reservationId())).isEqualTo(ReservationStatus.Canceled);
        assertThat(reservationSeats.findAllByReservation_ReservationId(original.reservationId())).isEmpty();
        assertRebookedByOtherUser(original.reservationId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"scheduler", "confirmation", "new-selection"})
    void expiredReservationCanBeRebookedAndConfirmedByAnotherUser(String expirationTrigger) {
        ReservationResponse original = create(userId, screeningId, firstSeatId, secondSeatId);
        backdateExpiry(original.reservationId());

        switch (expirationTrigger) {
            case "scheduler" -> scheduler.expirePendingReservations();
            case "confirmation" -> assertConflict(
                    () -> service.confirmReservation(userId, original.reservationId()),
                    ErrorCode.RESERVATION_EXPIRED
            );
            case "new-selection" -> { /* 아래의 재선택 요청에서 만료 처리를 검증한다. */ }
            default -> throw new IllegalArgumentException(expirationTrigger);
        }

        assertRebookedByOtherUser(original.reservationId());
        assertThat(savedStatus(original.reservationId())).isEqualTo(ReservationStatus.Expired);
        assertThat(reservationSeats.findAllByReservation_ReservationId(original.reservationId())).isEmpty();
    }

    private void assertRebookedByOtherUser(Long originalReservationId) {
        ReservationResponse replacement = create(otherUserId, screeningId, firstSeatId, secondSeatId);
        assertThat(replacement.reservationId()).isNotEqualTo(originalReservationId);
        assertThat(replacement.status()).isEqualTo(ReservationStatus.Pending);
        assertThat(service.confirmReservation(otherUserId, replacement.reservationId()).status())
                .isEqualTo(ReservationStatus.Reserved);

        // 이전 예매의 만료 처리가 새 예매의 좌석을 해제하면 안 된다.
        expirationService.expireReservation(originalReservationId);
        assertThat(reservationSeats.findAllByReservation_ReservationId(replacement.reservationId())).hasSize(2);
        assertConflict(() -> create(userId, screeningId, firstSeatId), ErrorCode.SEAT_UNAVAILABLE);
    }

    @Test
    void concurrentSelectionOfSameSeatHasOneWinnerAndNoOrphanReservation() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> selectAfter(start, userId));
            var second = executor.submit(() -> selectAfter(start, otherUserId));
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(reservations.findAllByUser_IdOrderByReservationAtDesc(userId).size()
                + reservations.findAllByUser_IdOrderByReservationAtDesc(otherUserId).size()).isEqualTo(1);
    }

    @Test
    void lockingOneReservationDoesNotBlockSelectionOfDifferentSeatInSameScreening() throws Exception {
        ReservationResponse pending = create(userId, screeningId, firstSeatId);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var locker = executor.submit(() -> transaction.executeWithoutResult(ignored -> {
                reservations.findByIdWithLock(pending.reservationId()).orElseThrow();
                locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("락 해제 대기 시간 초과");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                var otherSelection = executor.submit(() -> create(otherUserId, screeningId, secondSeatId));
                assertThat(otherSelection.get(5, TimeUnit.SECONDS).status()).isEqualTo(ReservationStatus.Pending);
            } finally {
                release.countDown();
            }
            locker.get(5, TimeUnit.SECONDS);
        }
    }

    private boolean selectAfter(CountDownLatch start, Long selectingUserId) throws InterruptedException {
        start.await();
        try {
            create(selectingUserId, screeningId, firstSeatId);
            return true;
        } catch (GlobalException e) {
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SEAT_UNAVAILABLE);
            return false;
        }
    }

    private ReservationResponse create(Long selectingUserId, Long targetScreeningId, Long... seatIds) {
        return service.createReservation(selectingUserId, new ReservationCreateRequest(
                targetScreeningId, 0, 0, seatIds.length, 0, List.of(seatIds)
        ));
    }

    private void backdateExpiry(Long reservationId) {
        jdbc.update("update reservation set expires_at = ? where reservation_id = ?",
                LocalDateTime.now().minusSeconds(1), reservationId);
    }

    private ReservationStatus savedStatus(Long reservationId) {
        return reservations.findById(reservationId).orElseThrow().getStatus();
    }

    private void assertConflict(Runnable operation, ErrorCode expected) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(GlobalException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }

    private <T> T persist(T entity) {
        entityManager.persist(entity);
        return entity;
    }
}
