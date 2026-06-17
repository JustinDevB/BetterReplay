package me.justindevb.replay.util;

import java.util.Optional;

public final class ReplayNames {

    public static final int MAX_NAME_LENGTH = 64;
    private static final String DISALLOWED_CHARACTERS = "\\/:*?\"<>|\u00A7";

    private ReplayNames() {
    }

    public static Optional<String> validateReplayName(String name) {
        return validate(name, "Replay");
    }

    public static Optional<String> validateRecordingName(String name) {
        return validate(name, "Recording");
    }

    public static boolean isValidReplayName(String name) {
        return validateReplayName(name).isEmpty();
    }

    public static boolean isValidRecordingName(String name) {
        return validateRecordingName(name).isEmpty();
    }

    private static Optional<String> validate(String name, String label) {
        if (name == null || name.isBlank()) {
            return Optional.of(label + " name is required");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            return Optional.of(label + " names must be 1-" + MAX_NAME_LENGTH + " characters long");
        }
        for (int index = 0; index < name.length(); index++) {
            char current = name.charAt(index);
            if (Character.isISOControl(current)) {
                return Optional.of(label + " names may not contain control characters");
            }
            if (DISALLOWED_CHARACTERS.indexOf(current) >= 0) {
                return Optional.of(label + " names may not contain any of \\ / : * ? \" < > | or \u00A7");
            }
        }
        return Optional.empty();
    }
}
