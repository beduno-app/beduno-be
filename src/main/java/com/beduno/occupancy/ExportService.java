package com.beduno.occupancy;

import com.beduno.stay.StayService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ExportService {

    private final OccupancyService occupancyService;
    private final StayService stayService;
    private final MessageSource messageSource;

    @Transactional(readOnly = true)
    public String exportOccupancy(UUID propertyId, LocalDate date, String language) {
        var locale = toLocale(language);
        var sb = new StringBuilder();
        sb.append(msg("export.occupancy.header", locale)).append("\n");

        for (var room : occupancyService.getOccupancy(propertyId, date)) {
            if (room.occupants().isEmpty()) {
                sb.append(csvRow(
                        room.roomName(),
                        blankIfNull(room.floor()),
                        String.valueOf(room.capacity()),
                        String.valueOf(room.blockedSpots()),
                        "0",
                        "", "", ""
                )).append("\n");
            } else {
                for (var occupant : room.occupants()) {
                    sb.append(csvRow(
                            room.roomName(),
                            blankIfNull(room.floor()),
                            String.valueOf(room.capacity()),
                            String.valueOf(room.blockedSpots()),
                            String.valueOf(room.occupiedSpots()),
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

        for (var stay : stayService.getArrivals(propertyId, date)) {
            sb.append(csvRow(
                    stay.id().toString(),
                    stay.workerId().toString(),
                    stay.roomId().toString(),
                    blankIfNull(stay.dateFrom() != null ? stay.dateFrom().toString() : null),
                    blankIfNull(stay.dateTo() != null ? stay.dateTo().toString() : null),
                    localizedStatus(stay.status().name(), locale)
            )).append("\n");
        }
        return sb.toString();
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
                        ex.roomName(),
                        exLabel,
                        String.valueOf(ex.capacity()),
                        String.valueOf(ex.blockedSpots()),
                        String.valueOf(ex.occupiedSpots()),
                        "", "", ""
                )).append("\n");
            } else {
                for (var occupant : ex.occupants()) {
                    sb.append(csvRow(
                            ex.roomName(),
                            exLabel,
                            String.valueOf(ex.capacity()),
                            String.valueOf(ex.blockedSpots()),
                            String.valueOf(ex.occupiedSpots()),
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

    private String escapeCsvField(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
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

    private String blankIfNull(String value) {
        return value != null ? value : "";
    }
}
