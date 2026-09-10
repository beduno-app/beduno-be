package com.beduno.stay.constraint;

import com.beduno.TestBuilders;
import com.beduno.bed.Bed;
import com.beduno.bed.BedStatus;
import com.beduno.property.PropertyStatus;
import com.beduno.room.GenderRule;
import com.beduno.room.RoomStatus;
import com.beduno.stay.StayRepository;
import com.beduno.stay.constraint.impl.BedOccupancyConstraint;
import com.beduno.stay.constraint.impl.BlockedRoomConstraint;
import com.beduno.stay.constraint.impl.DoubleBookingConstraint;
import com.beduno.stay.constraint.impl.GenderConstraint;
import com.beduno.worker.Gender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConstraintEngineTest {

    @Mock
    private StayRepository stayRepository;

    private ConstraintEngine engine;

    @BeforeEach
    void setUp() {
        var constraints = List.<StayConstraint>of(
                new BlockedRoomConstraint(),
                new BedOccupancyConstraint(stayRepository),
                new DoubleBookingConstraint(stayRepository),
                new GenderConstraint()
        );
        engine = new ConstraintEngine(constraints);
    }

    private ConstraintContext ctx(
            com.beduno.worker.Worker worker,
            com.beduno.room.Room room,
            com.beduno.property.Property property) {
        return ctx(worker, room, property, null, null);
    }

    private ConstraintContext ctx(
            com.beduno.worker.Worker worker,
            com.beduno.room.Room room,
            com.beduno.property.Property property,
            Bed bed,
            UUID excludeStayId) {
        return new ConstraintContext(worker, room, property,
                LocalDate.now(), LocalDate.now().plusDays(7), excludeStayId, bed);
    }

    @Nested
    class BlockedRoomConstraintTests {

        @Test
        void shouldBlockOperation_whenRoomIsBlocked() {
            var room = TestBuilders.aRoom().status(RoomStatus.BLOCKED).build();
            var worker = TestBuilders.aWorker().build();
            var property = TestBuilders.aProperty().build();

            when(stayRepository.countOverlappingStaysForWorker(any(), any(), any(), any(), anyList())).thenReturn(0L);

            var result = engine.evaluate(ctx(worker, room, property));

            assertThat(result.isAllowed()).isFalse();
            assertThat(result.hardViolations())
                    .extracting(HardViolation::type)
                    .contains("ROOM_BLOCKED");
        }

        @Test
        void shouldBlockOperation_whenPropertyIsInactive() {
            var room = TestBuilders.aRoom().build();
            var worker = TestBuilders.aWorker().build();
            var property = TestBuilders.aProperty().status(PropertyStatus.INACTIVE).build();

            when(stayRepository.countOverlappingStaysForWorker(any(), any(), any(), any(), anyList())).thenReturn(0L);

            var result = engine.evaluate(ctx(worker, room, property));

            assertThat(result.isAllowed()).isFalse();
            assertThat(result.hardViolations())
                    .extracting(HardViolation::type)
                    .contains("PROPERTY_INACTIVE");
        }

        @Test
        void shouldAllow_whenRoomActiveAndPropertyActive() {
            var room = TestBuilders.aRoom().build();
            var worker = TestBuilders.aWorker().build();
            var property = TestBuilders.aProperty().build();

            when(stayRepository.countOverlappingStaysForWorker(any(), any(), any(), any(), anyList())).thenReturn(0L);

            var result = engine.evaluate(ctx(worker, room, property));

            assertThat(result.hardViolations()).noneMatch(v -> v.type().equals("ROOM_BLOCKED")
                    || v.type().equals("PROPERTY_INACTIVE"));
        }

        @Test
        void shouldBlockOperation_whenBedIsBlocked() {
            var room = TestBuilders.aRoom().build();
            var bed = TestBuilders.aBed().roomId(room.getId()).status(BedStatus.BLOCKED).build();
            var worker = TestBuilders.aWorker().build();
            var property = TestBuilders.aProperty().build();

            when(stayRepository.countActiveStaysInBed(any(), any(), any(), any(), anyList())).thenReturn(0L);
            when(stayRepository.countOverlappingStaysForWorker(any(), any(), any(), any(), anyList())).thenReturn(0L);

            var result = engine.evaluate(ctx(worker, room, property, bed, null));

            assertThat(result.isAllowed()).isFalse();
            assertThat(result.hardViolations())
                    .extracting(HardViolation::type)
                    .contains("BED_BLOCKED");
        }

        @Test
        void shouldAllow_whenBedIsActive() {
            var room = TestBuilders.aRoom().build();
            var bed = TestBuilders.aBed().roomId(room.getId()).status(BedStatus.ACTIVE).build();
            var worker = TestBuilders.aWorker().build();
            var property = TestBuilders.aProperty().build();

            when(stayRepository.countActiveStaysInBed(any(), any(), any(), any(), anyList())).thenReturn(0L);
            when(stayRepository.countOverlappingStaysForWorker(any(), any(), any(), any(), anyList())).thenReturn(0L);

            var result = engine.evaluate(ctx(worker, room, property, bed, null));

            assertThat(result.hardViolations()).noneMatch(v -> v.type().equals("BED_BLOCKED"));
        }
    }

    @Nested
    class BedOccupancyConstraintTests {

        @Test
        void shouldBeNoOp_whenBedIsNull() {
            var room = TestBuilders.aRoom().build();
            var worker = TestBuilders.aWorker().build();
            var property = TestBuilders.aProperty().build();

            when(stayRepository.countOverlappingStaysForWorker(any(), any(), any(), any(), anyList())).thenReturn(0L);

            var result = engine.evaluate(ctx(worker, room, property));

            assertThat(result.hardViolations()).noneMatch(v -> v.type().equals("BED_OCCUPIED"));
        }

        @Test
        void shouldBlockOperation_whenBedIsAlreadyOccupied() {
            var room = TestBuilders.aRoom().build();
            var bed = TestBuilders.aBed().roomId(room.getId()).build();
            var worker = TestBuilders.aWorker().build();
            var property = TestBuilders.aProperty().build();

            when(stayRepository.countActiveStaysInBed(any(), any(), any(), any(), anyList())).thenReturn(1L);
            when(stayRepository.countOverlappingStaysForWorker(any(), any(), any(), any(), anyList())).thenReturn(0L);

            var result = engine.evaluate(ctx(worker, room, property, bed, null));

            assertThat(result.isAllowed()).isFalse();
            assertThat(result.hardViolations())
                    .extracting(HardViolation::type)
                    .contains("BED_OCCUPIED");
        }

        @Test
        void shouldAllow_whenBedIsFree() {
            var room = TestBuilders.aRoom().build();
            var bed = TestBuilders.aBed().roomId(room.getId()).build();
            var worker = TestBuilders.aWorker().build();
            var property = TestBuilders.aProperty().build();

            when(stayRepository.countActiveStaysInBed(any(), any(), any(), any(), anyList())).thenReturn(0L);
            when(stayRepository.countOverlappingStaysForWorker(any(), any(), any(), any(), anyList())).thenReturn(0L);

            var result = engine.evaluate(ctx(worker, room, property, bed, null));

            assertThat(result.hardViolations()).noneMatch(v -> v.type().equals("BED_OCCUPIED"));
        }

        @Test
        void shouldExcludeCurrentStay_whenUpdating() {
            var room = TestBuilders.aRoom().build();
            var bed = TestBuilders.aBed().roomId(room.getId()).build();
            var worker = TestBuilders.aWorker().build();
            var property = TestBuilders.aProperty().build();
            var excludeId = UUID.randomUUID();

            var ctxWithExclude = ctx(worker, room, property, bed, excludeId);

            when(stayRepository.countActiveStaysInBedExcluding(any(), any(), any(), any(), anyList(), any())).thenReturn(0L);
            when(stayRepository.countOverlappingStaysForWorkerExcluding(any(), any(), any(), any(), anyList(), any())).thenReturn(0L);

            var result = engine.evaluate(ctxWithExclude);

            assertThat(result.hardViolations())
                    .noneMatch(v -> v.type().equals("BED_OCCUPIED"));
        }
    }

    @Nested
    class DoubleBookingConstraintTests {

        @Test
        void shouldBlockOperation_whenWorkerAlreadyHasOverlappingStay() {
            var room = TestBuilders.aRoom().build();
            var worker = TestBuilders.aWorker().build();
            var property = TestBuilders.aProperty().build();

            when(stayRepository.countOverlappingStaysForWorker(any(), any(), any(), any(), anyList())).thenReturn(1L);

            var result = engine.evaluate(ctx(worker, room, property));

            assertThat(result.isAllowed()).isFalse();
            assertThat(result.hardViolations())
                    .extracting(HardViolation::type)
                    .contains("DOUBLE_BOOKING");
        }

        @Test
        void shouldAllow_whenWorkerHasNoOverlappingStays() {
            var room = TestBuilders.aRoom().build();
            var worker = TestBuilders.aWorker().build();
            var property = TestBuilders.aProperty().build();

            when(stayRepository.countOverlappingStaysForWorker(any(), any(), any(), any(), anyList())).thenReturn(0L);

            var result = engine.evaluate(ctx(worker, room, property));

            assertThat(result.hardViolations())
                    .noneMatch(v -> v.type().equals("DOUBLE_BOOKING"));
        }

        @Test
        void shouldExcludeCurrentStay_whenUpdating() {
            var room = TestBuilders.aRoom().build();
            var worker = TestBuilders.aWorker().build();
            var property = TestBuilders.aProperty().build();
            var excludeId = UUID.randomUUID();

            var ctxWithExclude = ctx(worker, room, property, null, excludeId);

            when(stayRepository.countOverlappingStaysForWorkerExcluding(any(), any(), any(), any(), anyList(), any())).thenReturn(0L);

            var result = engine.evaluate(ctxWithExclude);

            assertThat(result.hardViolations())
                    .noneMatch(v -> v.type().equals("DOUBLE_BOOKING"));
        }
    }

    @Nested
    class GenderConstraintTests {

        @Test
        void shouldAddSoftViolation_whenMaleWorkerInFemaleOnlyRoom() {
            var room = TestBuilders.aRoom().genderRule(GenderRule.FEMALE_ONLY).build();
            var worker = TestBuilders.aWorker().gender(Gender.MALE).build();
            var property = TestBuilders.aProperty().build();

            when(stayRepository.countOverlappingStaysForWorker(any(), any(), any(), any(), anyList())).thenReturn(0L);

            var result = engine.evaluate(ctx(worker, room, property));

            assertThat(result.isAllowed()).isTrue();
            assertThat(result.hasWarnings()).isTrue();
            assertThat(result.softViolations())
                    .extracting(SoftViolation::type)
                    .contains("GENDER_MISMATCH");
        }

        @Test
        void shouldAddSoftViolation_whenFemaleWorkerInMaleOnlyRoom() {
            var room = TestBuilders.aRoom().genderRule(GenderRule.MALE_ONLY).build();
            var worker = TestBuilders.aWorker().gender(Gender.FEMALE).build();
            var property = TestBuilders.aProperty().build();

            when(stayRepository.countOverlappingStaysForWorker(any(), any(), any(), any(), anyList())).thenReturn(0L);

            var result = engine.evaluate(ctx(worker, room, property));

            assertThat(result.isAllowed()).isTrue();
            assertThat(result.hasWarnings()).isTrue();
            assertThat(result.softViolations())
                    .extracting(SoftViolation::type)
                    .contains("GENDER_MISMATCH");
        }

        @Test
        void shouldNotAddViolation_whenGenderRuleIsAny() {
            var room = TestBuilders.aRoom().genderRule(GenderRule.MIXED).build();
            var worker = TestBuilders.aWorker().gender(Gender.FEMALE).build();
            var property = TestBuilders.aProperty().build();

            when(stayRepository.countOverlappingStaysForWorker(any(), any(), any(), any(), anyList())).thenReturn(0L);

            var result = engine.evaluate(ctx(worker, room, property));

            assertThat(result.softViolations()).isEmpty();
        }

        @Test
        void shouldNotAddViolation_whenMaleWorkerInMaleOnlyRoom() {
            var room = TestBuilders.aRoom().genderRule(GenderRule.MALE_ONLY).build();
            var worker = TestBuilders.aWorker().gender(Gender.MALE).build();
            var property = TestBuilders.aProperty().build();

            when(stayRepository.countOverlappingStaysForWorker(any(), any(), any(), any(), anyList())).thenReturn(0L);

            var result = engine.evaluate(ctx(worker, room, property));

            assertThat(result.softViolations()).noneMatch(v -> v.type().equals("GENDER_MISMATCH"));
        }

        @Test
        void shouldAddSoftViolation_whenOtherGenderInGenderedRoom() {
            var room = TestBuilders.aRoom().genderRule(GenderRule.MALE_ONLY).build();
            var worker = TestBuilders.aWorker().gender(Gender.OTHER).build();
            var property = TestBuilders.aProperty().build();

            when(stayRepository.countOverlappingStaysForWorker(any(), any(), any(), any(), anyList())).thenReturn(0L);

            var result = engine.evaluate(ctx(worker, room, property));

            assertThat(result.hasWarnings()).isTrue();
            assertThat(result.softViolations())
                    .extracting(SoftViolation::type)
                    .contains("GENDER_MISMATCH");
        }
    }

    @Nested
    class MultipleViolationTests {

        @Test
        void shouldCollectMultipleHardViolations_whenSeveralConstraintsFail() {
            var room = TestBuilders.aRoom().status(RoomStatus.BLOCKED).build();
            var worker = TestBuilders.aWorker().build();
            var property = TestBuilders.aProperty().status(PropertyStatus.INACTIVE).build();

            when(stayRepository.countOverlappingStaysForWorker(any(), any(), any(), any(), anyList())).thenReturn(1L);

            var result = engine.evaluate(ctx(worker, room, property));

            assertThat(result.isAllowed()).isFalse();
            assertThat(result.hardViolations()).hasSizeGreaterThanOrEqualTo(2);
        }

        @Test
        void shouldReturnAllowed_whenNoViolations() {
            var room = TestBuilders.aRoom().build();
            var worker = TestBuilders.aWorker().build();
            var property = TestBuilders.aProperty().build();

            when(stayRepository.countOverlappingStaysForWorker(any(), any(), any(), any(), anyList())).thenReturn(0L);

            var result = engine.evaluate(ctx(worker, room, property));

            assertThat(result.isAllowed()).isTrue();
            assertThat(result.hasWarnings()).isFalse();
        }
    }
}
