package com.beduno.occupancy;

import com.beduno.bed.Bed;
import com.beduno.bed.BedRepository;
import com.beduno.bed.BedStatus;
import com.beduno.common.security.TenantContext;
import com.beduno.occupancy.dto.InspectionDiscrepancyResponse;
import com.beduno.occupancy.dto.InspectionReportRequest;
import com.beduno.occupancy.dto.InspectionRoomEntry;
import com.beduno.occupancy.dto.OccupancyExceptionResponse;
import com.beduno.occupancy.dto.OccupantSummary;
import com.beduno.occupancy.dto.RoomActualOccupancy;
import com.beduno.occupancy.dto.RoomDiscrepancy;
import com.beduno.occupancy.dto.RoomOccupancyResponse;
import com.beduno.occupancy.dto.WorkerDiscrepancy;
import com.beduno.property.PropertyRepository;
import com.beduno.room.Room;
import com.beduno.room.RoomRepository;
import com.beduno.stay.Stay;
import com.beduno.stay.StayRepository;
import com.beduno.stay.StayStatus;
import com.beduno.worker.Worker;
import com.beduno.worker.WorkerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OccupancyService {

    private static final List<StayStatus> CHECKED_IN_STATUS = List.of(StayStatus.CHECKED_IN);
    private static final List<StayStatus> ACTIVE_STATUSES = List.of(
            StayStatus.CHECKED_IN, StayStatus.EXPECTED_TODAY);

    private final StayRepository stayRepository;
    private final RoomRepository roomRepository;
    private final WorkerRepository workerRepository;
    private final PropertyRepository propertyRepository;
    private final BedRepository bedRepository;

    @Transactional(readOnly = true)
    public List<RoomOccupancyResponse> getOccupancy(UUID propertyId, LocalDate date) {
        var agencyId = TenantContext.requireAgencyId();
        var rooms = roomRepository.findAllByAgencyIdAndPropertyId(agencyId, propertyId);
        var stays = stayRepository.findActiveStaysForPropertyOnDate(agencyId, propertyId, date, CHECKED_IN_STATUS);
        var workerMap = loadWorkers(stays, agencyId);
        var beds = loadBeds(agencyId, rooms);
        var bedCounts = bedCountsByRoom(beds);
        var bedLabelById = bedLabelById(beds);

        var staysByRoom = stays.stream().collect(Collectors.groupingBy(Stay::getRoomId));

        return rooms.stream().map(room -> {
            var roomStays = staysByRoom.getOrDefault(room.getId(), List.of());
            var occupants = toOccupantSummaries(roomStays, workerMap, bedLabelById);
            var counts = bedCounts.getOrDefault(room.getId(), BedCounts.EMPTY);
            return new RoomOccupancyResponse(
                    room.getId(),
                    room.getRoomNumber(),
                    room.getFloor(),
                    counts.total(),
                    counts.active(),
                    occupants.size(),
                    occupants
            );
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<OccupancyExceptionResponse> getExceptions(UUID propertyId, LocalDate date) {
        var agencyId = TenantContext.requireAgencyId();
        var rooms = roomRepository.findAllByAgencyIdAndPropertyId(agencyId, propertyId);
        var stays = stayRepository.findActiveStaysForPropertyOnDate(agencyId, propertyId, date, ACTIVE_STATUSES);
        var workerMap = loadWorkers(stays, agencyId);
        var beds = loadBeds(agencyId, rooms);
        var bedCounts = bedCountsByRoom(beds);
        var bedLabelById = bedLabelById(beds);

        var checkedInByRoom = stays.stream()
                .filter(s -> s.getStatus() == StayStatus.CHECKED_IN)
                .collect(Collectors.groupingBy(Stay::getRoomId));
        var expectedByRoom = stays.stream()
                .filter(s -> s.getStatus() == StayStatus.EXPECTED_TODAY)
                .collect(Collectors.groupingBy(Stay::getRoomId));

        var exceptions = new ArrayList<OccupancyExceptionResponse>();
        for (var room : rooms) {
            var checkedIn = checkedInByRoom.getOrDefault(room.getId(), List.of());
            var expected = expectedByRoom.getOrDefault(room.getId(), List.of());
            var counts = bedCounts.getOrDefault(room.getId(), BedCounts.EMPTY);

            // A data-integrity safety net over historical/backfilled data, not the live guard --
            // going forward BedOccupancyConstraint (phase 2) and the bed-blocked check (phase 3)
            // prevent new violations at write time once phase 4 wires bed resolution in.
            if (checkedIn.size() > counts.active()) {
                exceptions.add(new OccupancyExceptionResponse(
                        room.getId(), room.getRoomNumber(), "OVER_CAPACITY",
                        counts.total(), counts.active(), checkedIn.size(),
                        toOccupantSummaries(checkedIn, workerMap, bedLabelById)
                ));
            } else if (!expected.isEmpty()) {
                exceptions.add(new OccupancyExceptionResponse(
                        room.getId(), room.getRoomNumber(), "PENDING_ARRIVAL",
                        counts.total(), counts.active(), checkedIn.size(),
                        toOccupantSummaries(expected, workerMap, bedLabelById)
                ));
            }
        }
        return exceptions;
    }

    @Transactional(readOnly = true)
    public List<InspectionRoomEntry> getInspectionRoster(UUID propertyId, LocalDate date) {
        var agencyId = TenantContext.requireAgencyId();
        var rooms = roomRepository.findAllByAgencyIdAndPropertyId(agencyId, propertyId);
        var stays = stayRepository.findActiveStaysForPropertyOnDate(agencyId, propertyId, date, ACTIVE_STATUSES);
        var workerMap = loadWorkers(stays, agencyId);
        var bedLabelById = bedLabelById(loadBeds(agencyId, rooms));

        var checkedInByRoom = stays.stream()
                .filter(s -> s.getStatus() == StayStatus.CHECKED_IN)
                .collect(Collectors.groupingBy(Stay::getRoomId));
        var allActiveByRoom = stays.stream()
                .collect(Collectors.groupingBy(Stay::getRoomId));

        return rooms.stream().map(room -> {
            var allActive = allActiveByRoom.getOrDefault(room.getId(), List.of());
            var checkedIn = checkedInByRoom.getOrDefault(room.getId(), List.of());
            return new InspectionRoomEntry(
                    room.getId(),
                    room.getRoomNumber(),
                    room.getFloor(),
                    toOccupantSummaries(allActive, workerMap, bedLabelById),
                    toOccupantSummaries(checkedIn, workerMap, bedLabelById)
            );
        }).toList();
    }

    @Transactional(readOnly = true)
    public InspectionDiscrepancyResponse submitInspectionReport(UUID propertyId,
                                                                LocalDate date,
                                                                InspectionReportRequest request) {
        var agencyId = TenantContext.requireAgencyId();
        var rooms = roomRepository.findAllByAgencyIdAndPropertyId(agencyId, propertyId);
        var stays = stayRepository.findActiveStaysForPropertyOnDate(
                agencyId, propertyId, date, List.of(StayStatus.CHECKED_IN));
        var workerMap = loadWorkers(stays, agencyId);

        var checkedInByRoom = stays.stream()
                .collect(Collectors.groupingBy(Stay::getRoomId));
        var roomNumberById = rooms.stream()
                .collect(Collectors.toMap(r -> r.getId(), r -> r.getRoomNumber()));

        var reportByRoomId = request.rooms().stream()
                .collect(Collectors.toMap(RoomActualOccupancy::roomId, RoomActualOccupancy::presentWorkerIds));

        var discrepancies = new ArrayList<RoomDiscrepancy>();
        for (var roomId : roomNumberById.keySet()) {
            var expectedStays = checkedInByRoom.getOrDefault(roomId, List.of());
            var presentWorkerIds = reportByRoomId.getOrDefault(roomId, List.of());
            var expectedWorkerIds = expectedStays.stream().map(Stay::getWorkerId).collect(Collectors.toSet());

            var items = new ArrayList<WorkerDiscrepancy>();
            for (var expectedId : expectedWorkerIds) {
                if (!presentWorkerIds.contains(expectedId)) {
                    items.add(new WorkerDiscrepancy(expectedId, "EXPECTED_NOT_PRESENT"));
                }
            }
            for (var presentId : presentWorkerIds) {
                if (!expectedWorkerIds.contains(presentId)) {
                    items.add(new WorkerDiscrepancy(presentId, "UNEXPECTED_PRESENT"));
                }
            }
            if (!items.isEmpty()) {
                discrepancies.add(new RoomDiscrepancy(roomId, roomNumberById.get(roomId), items));
            }
        }
        return new InspectionDiscrepancyResponse(discrepancies, !discrepancies.isEmpty());
    }

    private Map<UUID, Worker> loadWorkers(List<Stay> stays, UUID agencyId) {
        var workerIds = stays.stream().map(Stay::getWorkerId).distinct().toList();
        if (workerIds.isEmpty()) {
            return Map.of();
        }
        return workerRepository.findAllById(workerIds).stream()
                .collect(Collectors.toMap(Worker::getId, w -> w));
    }

    private List<OccupantSummary> toOccupantSummaries(List<Stay> stays, Map<UUID, Worker> workerMap,
                                                        Map<UUID, String> bedLabelById) {
        return stays.stream().map(s -> {
            var w = workerMap.get(s.getWorkerId());
            return new OccupantSummary(
                    s.getId(), s.getWorkerId(),
                    w != null ? w.getFirstName() : null,
                    w != null ? w.getLastName() : null,
                    s.getBedId(), bedLabelById.get(s.getBedId())
            );
        }).toList();
    }

    /**
     * Loaded once per request for the whole property rather than per room, matching how stays and
     * workers are already batched above.
     */
    private List<Bed> loadBeds(UUID agencyId, List<Room> rooms) {
        if (rooms.isEmpty()) {
            return List.of();
        }
        var roomIds = rooms.stream().map(Room::getId).collect(Collectors.toSet());
        return bedRepository.findAllByAgencyIdAndRoomIdIn(agencyId, roomIds);
    }

    private Map<UUID, BedCounts> bedCountsByRoom(List<Bed> beds) {
        return beds.stream().collect(Collectors.groupingBy(Bed::getRoomId,
                Collectors.collectingAndThen(Collectors.toList(), BedCounts::of)));
    }

    private Map<UUID, String> bedLabelById(List<Bed> beds) {
        return beds.stream().collect(Collectors.toMap(Bed::getId, Bed::getLabel));
    }

    /** total beds vs. beds not blocked -- the direct translation of the old capacity/blockedSpots pair. */
    private record BedCounts(int total, int active) {
        static final BedCounts EMPTY = new BedCounts(0, 0);

        static BedCounts of(List<Bed> beds) {
            var active = (int) beds.stream().filter(b -> b.getStatus() == BedStatus.ACTIVE).count();
            return new BedCounts(beds.size(), active);
        }
    }
}
