package com.beduno.occupancy;

import com.beduno.occupancy.dto.InspectionDiscrepancyResponse;
import com.beduno.occupancy.dto.InspectionReportRequest;
import com.beduno.occupancy.dto.InspectionRoomEntry;
import com.beduno.occupancy.dto.OccupancyExceptionResponse;
import com.beduno.occupancy.dto.RoomOccupancyResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Tag(name = "Occupancy", description = "Occupancy views, exceptions, inspection, and CSV exports")
@RestController
@RequestMapping("/api/v1/properties/{propertyId}")
@RequiredArgsConstructor
public class OccupancyController {

    private final OccupancyService occupancyService;
    private final ExportService exportService;

    @Operation(summary = "Room occupancy", description = "Returns room-by-room list of current occupants for a property on a given date")
    @GetMapping("/occupancy")
    @PreAuthorize("hasAnyRole('AGENCY_ADMIN', 'AGENCY_PLANNER', 'PROPERTY_ADMIN', 'FRONT_DESK')")
    public ResponseEntity<List<RoomOccupancyResponse>> getOccupancy(
            @PathVariable UUID propertyId,
            @RequestParam(required = false) LocalDate date) {
        return ResponseEntity.ok(occupancyService.getOccupancy(propertyId, date != null ? date : LocalDate.now()));
    }

    @Operation(summary = "Occupancy exceptions", description = "Returns over-capacity rooms and unassigned stays for a property on a given date")
    @GetMapping("/exceptions")
    @PreAuthorize("hasAnyRole('AGENCY_ADMIN', 'AGENCY_PLANNER', 'PROPERTY_ADMIN', 'FRONT_DESK')")
    public ResponseEntity<List<OccupancyExceptionResponse>> getExceptions(
            @PathVariable UUID propertyId,
            @RequestParam(required = false) LocalDate date) {
        return ResponseEntity.ok(occupancyService.getExceptions(propertyId, date != null ? date : LocalDate.now()));
    }

    @Operation(summary = "Inspection roster", description = "Room-by-room roster for nightly inspection")
    @GetMapping("/inspection")
    @PreAuthorize("hasRole('PROPERTY_ADMIN')")
    public ResponseEntity<List<InspectionRoomEntry>> getInspectionRoster(
            @PathVariable UUID propertyId,
            @RequestParam(required = false) LocalDate date) {
        return ResponseEntity.ok(occupancyService.getInspectionRoster(propertyId, date != null ? date : LocalDate.now()));
    }

    @Operation(summary = "Submit inspection report", description = "Reports discrepancies: expected workers not present, unexpected workers present")
    @PostMapping("/inspection")
    @PreAuthorize("hasRole('PROPERTY_ADMIN')")
    public ResponseEntity<InspectionDiscrepancyResponse> submitInspectionReport(
            @PathVariable UUID propertyId,
            @RequestParam(required = false) LocalDate date,
            @Valid @RequestBody InspectionReportRequest request) {
        return ResponseEntity.ok(occupancyService.submitInspectionReport(
                propertyId, date != null ? date : LocalDate.now(), request));
    }

    @Operation(summary = "Export occupancy CSV", description = "Downloads nightly occupancy list as CSV. Supports language parameter (EN, PL, DE, RU, UA).")
    @GetMapping("/occupancy/export")
    @PreAuthorize("hasAnyRole('AGENCY_ADMIN', 'AGENCY_PLANNER', 'PROPERTY_ADMIN')")
    public ResponseEntity<byte[]> exportOccupancy(
            @PathVariable UUID propertyId,
            @RequestParam(required = false) LocalDate date,
            @RequestParam(required = false, defaultValue = "EN") String language) {
        var csv = exportService.exportOccupancy(propertyId, date != null ? date : LocalDate.now(), language);
        return csvResponse(csv, "occupancy");
    }

    @Operation(summary = "Export arrivals CSV", description = "Downloads arrivals list as CSV. Supports language parameter (EN, PL, DE, RU, UA).")
    @GetMapping("/arrivals/export")
    @PreAuthorize("hasAnyRole('AGENCY_ADMIN', 'AGENCY_PLANNER', 'PROPERTY_ADMIN', 'FRONT_DESK')")
    public ResponseEntity<byte[]> exportArrivals(
            @PathVariable UUID propertyId,
            @RequestParam(required = false) LocalDate date,
            @RequestParam(required = false, defaultValue = "EN") String language) {
        var csv = exportService.exportArrivals(propertyId, date != null ? date : LocalDate.now(), language);
        return csvResponse(csv, "arrivals");
    }

    @Operation(summary = "Export exceptions CSV", description = "Downloads exception report as CSV. Supports language parameter (EN, PL, DE, RU, UA).")
    @GetMapping("/exceptions/export")
    @PreAuthorize("hasAnyRole('AGENCY_ADMIN', 'AGENCY_PLANNER', 'PROPERTY_ADMIN')")
    public ResponseEntity<byte[]> exportExceptions(
            @PathVariable UUID propertyId,
            @RequestParam(required = false) LocalDate date,
            @RequestParam(required = false, defaultValue = "EN") String language) {
        var csv = exportService.exportExceptions(propertyId, date != null ? date : LocalDate.now(), language);
        return csvResponse(csv, "exceptions");
    }

    private ResponseEntity<byte[]> csvResponse(String csv, String filePrefix) {
        var bytes = csv.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv;charset=UTF-8"));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(filePrefix + "_" + LocalDate.now() + ".csv").build());
        return ResponseEntity.ok().headers(headers).body(bytes);
    }
}
