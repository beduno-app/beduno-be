package com.beduno.occupancy;

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

    @Transactional(readOnly = true)
    public List<RoomOccupancyResponse> getOccupancy(UUID propertyId, LocalDate date) {
        var agencyId = TenantContext.requireAgencyId();
        var rooms = roomRepository.findAllByAgencyIdAndPropertyId(agencyId, propertyId);
        var stays = stayRepository.findActiveStaysForPropertyOnDate(agencyId, propertyId, date, CHECKED_IN_STATUS);
        var workerMap = loadWorkers(stays, agencyId);

        var staysByRoom = stays.stream().collect(Collectors.groupingBy(Stay::getRoomId));

        return rooms.stream().map(room -> {
            var roomStays = staysByRoom.getOrDefault(room.getId(), List.of());
            var occupants = toOccupantSummaries(roomStays, workerMap);
            return new RoomOccupancyResponse(
                    room.getId(),
                    room.getName(),
                    room.getFloor(),
                    room.getCapacity(),
                    room.getBlockedSpots(),
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

            if (checkedIn.size() > room.availableSpots()) {
                exceptions.add(new OccupancyExceptionResponse(
                        room.getId(), room.getName(), "OVER_CAPACITY",
                        room.getCapacity(), room.getBlockedSpots(), checkedIn.size(),
                        toOccupantSummaries(checkedIn, workerMap)
                ));
            } else if (!expected.isEmpty()) {
                exceptions.add(new OccupancyExceptionResponse(
                        room.getId(), room.getName(), "PENDING_ARRIVAL",
                        room.getCapacity(), room.getBlockedSpots(), checkedIn.size(),
                        toOccupantSummaries(expected, workerMap)
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
                    room.getName(),
                    room.getFloor(),
                    toOccupantSummaries(allActive, workerMap),
                    toOccupantSummaries(checkedIn, workerMap)
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
        var roomNameById = rooms.stream()
                .collect(Collectors.toMap(r -> r.getId(), r -> r.getName()));

        var reportByRoomId = request.rooms().stream()
                .collect(Collectors.toMap(RoomActualOccupancy::roomId, RoomActualOccupancy::presentWorkerIds));

        var discrepancies = new ArrayList<RoomDiscrepancy>();
        for (var roomId : roomNameById.keySet()) {
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
                discrepancies.add(new RoomDiscrepancy(roomId, roomNameById.get(roomId), items));
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

    private List<OccupantSummary> toOccupantSummaries(List<Stay> stays, Map<UUID, Worker> workerMap) {
        return stays.stream().map(s -> {
            var w = workerMap.get(s.getWorkerId());
            return new OccupantSummary(
                    s.getId(), s.getWorkerId(),
                    w != null ? w.getFirstName() : null,
                    w != null ? w.getLastName() : null
            );
        }).toList();
    }
}
