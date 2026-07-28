package moe.zl.breakinfalsus.output;

import androidx.annotation.NonNull;

import java.util.Locale;

public enum HidMouseMode {
    RELATIVE,
    ABSOLUTE;

    @NonNull
    public static HidMouseMode fromPreference(String value) {
        if (value == null) {
            return RELATIVE;
        }
        try {
            return HidMouseMode.valueOf(value.trim().toUpperCase(Locale.US));
        } catch (IllegalArgumentException exception) {
            return RELATIVE;
        }
    }
}
