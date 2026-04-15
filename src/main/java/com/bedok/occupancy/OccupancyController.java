package com.bedok.occupancy;

import com.bedok.occupancy.dto.InspectionDiscrepancyResponse;
import com.bedok.occupancy.dto.InspectionReportRequest;
import com.bedok.occupancy.dto.InspectionRoomEntry;
import com.bedok.occupancy.dto.OccupancyExceptionResponse;
import com.bedok.occupancy.dto.RoomOccupancyResponse;
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

@RestController
@RequestMapping("/api/v1/properties/{propertyId}")
@RequiredArgsConstructor
public class OccupancyController {

    private final OccupancyService occupancyService;
    private final ExportService exportService;

    @GetMapping("/occupancy")
    @PreAuthorize("hasAnyRole('AGENCY_ADMIN', 'AGENCY_PLANNER', 'PROPERTY_ADMIN', 'FRONT_DESK')")
    public ResponseEntity<List<RoomOccupancyResponse>> getOccupancy(
            @PathVariable UUID propertyId,
            @RequestParam(required = false) LocalDate date) {
        return ResponseEntity.ok(occupancyService.getOccupancy(propertyId, date != null ? date : LocalDate.now()));
    }

    @GetMapping("/exceptions")
    @PreAuthorize("hasAnyRole('AGENCY_ADMIN', 'AGENCY_PLANNER', 'PROPERTY_ADMIN', 'FRONT_DESK')")
    public ResponseEntity<List<OccupancyExceptionResponse>> getExceptions(
            @PathVariable UUID propertyId,
            @RequestParam(required = false) LocalDate date) {
        return ResponseEntity.ok(occupancyService.getExceptions(propertyId, date != null ? date : LocalDate.now()));
    }

    @GetMapping("/inspection")
    @PreAuthorize("hasRole('PROPERTY_ADMIN')")
    public ResponseEntity<List<InspectionRoomEntry>> getInspectionRoster(
            @PathVariable UUID propertyId,
            @RequestParam(required = false) LocalDate date) {
        return ResponseEntity.ok(occupancyService.getInspectionRoster(propertyId, date != null ? date : LocalDate.now()));
    }

    @PostMapping("/inspection")
    @PreAuthorize("hasRole('PROPERTY_ADMIN')")
    public ResponseEntity<InspectionDiscrepancyResponse> submitInspectionReport(
            @PathVariable UUID propertyId,
            @RequestParam(required = false) LocalDate date,
            @Valid @RequestBody InspectionReportRequest request) {
        return ResponseEntity.ok(occupancyService.submitInspectionReport(
                propertyId, date != null ? date : LocalDate.now(), request));
    }

    @GetMapping("/occupancy/export")
    @PreAuthorize("hasAnyRole('AGENCY_ADMIN', 'AGENCY_PLANNER', 'PROPERTY_ADMIN')")
    public ResponseEntity<byte[]> exportOccupancy(
            @PathVariable UUID propertyId,
            @RequestParam(required = false) LocalDate date,
            @RequestParam(required = false, defaultValue = "EN") String language) {
        var csv = exportService.exportOccupancy(propertyId, date != null ? date : LocalDate.now(), language);
        return csvResponse(csv, "occupancy");
    }

    @GetMapping("/arrivals/export")
    @PreAuthorize("hasAnyRole('AGENCY_ADMIN', 'AGENCY_PLANNER', 'PROPERTY_ADMIN', 'FRONT_DESK')")
    public ResponseEntity<byte[]> exportArrivals(
            @PathVariable UUID propertyId,
            @RequestParam(required = false) LocalDate date,
            @RequestParam(required = false, defaultValue = "EN") String language) {
        var csv = exportService.exportArrivals(propertyId, date != null ? date : LocalDate.now(), language);
        return csvResponse(csv, "arrivals");
    }

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
