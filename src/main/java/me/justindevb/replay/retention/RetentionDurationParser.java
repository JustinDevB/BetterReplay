package me.justindevb.replay.retention;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RetentionDurationParser {

    private static final Pattern DURATION_PATTERN = Pattern.compile("^(\\d+)([smhd])$");

    private RetentionDurationParser() {
    }

    public static Duration parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Duration value must not be null");
        }

        Matcher matcher = DURATION_PATTERN.matcher(value.trim().toLowerCase(Locale.ROOT));
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Unsupported duration value: " + value);
        }

        long amount = Long.parseLong(matcher.group(1));
        if (amount <= 0) {
            throw new IllegalArgumentException("Duration amount must be positive: " + value);
        }

        return switch (matcher.group(2)) {
            case "s" -> Duration.ofSeconds(amount);
            case "m" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            case "d" -> Duration.ofDays(amount);
            default -> throw new IllegalArgumentException("Unsupported duration unit: " + value);
        };
    }
}