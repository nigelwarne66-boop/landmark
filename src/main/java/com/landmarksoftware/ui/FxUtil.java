package com.landmarksoftware.ui;

import javafx.scene.control.DatePicker;
import javafx.util.StringConverter;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Shared JavaFX helpers used across controllers.
 */
public final class FxUtil {

    private static final DateTimeFormatter AU_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public static final StringConverter<LocalDate> AU_DATE_CONVERTER = new StringConverter<>() {
        @Override public String toString(LocalDate d) {
            return d == null ? "" : AU_FMT.format(d);
        }
        @Override public LocalDate fromString(String s) {
            return (s == null || s.isBlank()) ? null : LocalDate.parse(s.trim(), AU_FMT);
        }
    };

    private FxUtil() {}

    /** Creates a DatePicker with dd/MM/yyyy format, ignoring the system locale. */
    public static DatePicker auDatePicker() {
        DatePicker p = new DatePicker();
        p.setConverter(AU_DATE_CONVERTER);
        return p;
    }

    /** Creates a DatePicker with dd/MM/yyyy format pre-set to the given value. */
    public static DatePicker auDatePicker(LocalDate value) {
        DatePicker p = new DatePicker(value);
        p.setConverter(AU_DATE_CONVERTER);
        return p;
    }

    /** Applies dd/MM/yyyy format to one or more existing DatePicker instances. */
    public static void setAuDate(DatePicker... pickers) {
        for (DatePicker p : pickers) {
            if (p != null) p.setConverter(AU_DATE_CONVERTER);
        }
    }
}
