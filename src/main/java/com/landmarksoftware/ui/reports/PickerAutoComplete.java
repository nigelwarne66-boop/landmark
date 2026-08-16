package com.landmarksoftware.ui.reports;

import javafx.scene.control.ComboBox;
import javafx.scene.input.KeyCode;
import javafx.util.StringConverter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Adds type-ahead filtering to a {@link ComboBox} of arbitrary items.
 *
 * <p>As the user types in the editor, the dropdown is filtered to items whose
 * display text ({@code toString()}) contains the typed text (case-insensitive),
 * with prefix matches ordered first, and the popup opens — so typing
 * {@code "002-1"} jumps straight to items whose code starts with {@code 002-1}.
 *
 * <p>ENTER, or moving focus away (e.g. clicking the Filter button), commits the
 * first matching item so callers reading {@code getSelectionModel().getSelectedItem()}
 * always get a real value. Rendering relies on each item's {@code toString()}.
 *
 * <p>Call {@code install} once, after the ComboBox's items have been populated.
 */
public final class PickerAutoComplete {

    private PickerAutoComplete() {}

    public static <T> void install(ComboBox<T> combo) {
        final List<T> master = new ArrayList<>(combo.getItems());
        final boolean[] mutating = {false};

        combo.setEditable(true);
        combo.setVisibleRowCount(12);

        combo.setConverter(new StringConverter<T>() {
            @Override public String toString(T t) { return t == null ? "" : t.toString(); }
            @Override public T fromString(String s) {
                if (s != null) for (T t : master) if (t.toString().equals(s)) return t;
                return combo.getValue();   // partial text — keep current selection
            }
        });

        // Filter the dropdown as the user types.
        combo.getEditor().textProperty().addListener((obs, old, txt) -> {
            if (mutating[0]) return;
            T sel = combo.getValue();
            if (sel != null && sel.toString().equals(txt)) return;   // text set by a selection
            mutating[0] = true;
            try {
                String q = txt == null ? "" : txt.trim().toLowerCase();
                List<T> filtered = q.isEmpty() ? master : master.stream()
                    .filter(t -> t.toString().toLowerCase().contains(q))
                    .sorted(Comparator.<T>comparingInt(t -> t.toString().toLowerCase().startsWith(q) ? 0 : 1))
                    .collect(Collectors.toList());
                combo.getItems().setAll(filtered);
                combo.getEditor().setText(txt);
                combo.getEditor().positionCaret(txt == null ? 0 : txt.length());
                if (!filtered.isEmpty() && combo.getEditor().isFocused()) combo.show();
                else combo.hide();
            } finally {
                mutating[0] = false;
            }
        });

        // ENTER commits the first filtered item.
        combo.getEditor().setOnKeyReleased(ev -> {
            if (ev.getCode() == KeyCode.ENTER && !combo.getItems().isEmpty()) {
                commit(combo, combo.getItems().get(0), mutating);
                combo.hide();
            }
        });

        // Focus gained on a committed field → show the full list again.
        // Focus lost with partial text → commit the first match.
        combo.getEditor().focusedProperty().addListener((o, was, isNow) -> {
            if (mutating[0]) return;
            String txt = combo.getEditor().getText();
            T cur = combo.getValue();
            if (isNow) {
                if (cur != null && cur.toString().equals(txt)) {
                    mutating[0] = true;
                    combo.getItems().setAll(master);
                    mutating[0] = false;
                }
            } else {
                if (cur != null && cur.toString().equals(txt)) return;
                T match = null;
                for (T t : master) if (t.toString().equals(txt)) { match = t; break; }
                if (match == null && txt != null && !txt.isBlank() && !combo.getItems().isEmpty())
                    match = combo.getItems().get(0);
                if (match != null) commit(combo, match, mutating);
            }
        });
    }

    private static <T> void commit(ComboBox<T> combo, T item, boolean[] mutating) {
        mutating[0] = true;
        try {
            combo.getSelectionModel().select(item);
            combo.getEditor().setText(item.toString());
            combo.getEditor().positionCaret(item.toString().length());
        } finally {
            mutating[0] = false;
        }
    }
}
