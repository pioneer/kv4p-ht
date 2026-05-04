/*
kv4p HT (see http://kv4p.com)
Copyright (C) 2024 Vance Vagell

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.
*/

package com.vagell.kv4pht.radio;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.UUID;

import lombok.Getter;

/**
 * Persistent description of a paired Bluetooth LE PTT button. Captures everything
 * the {@link BlePttManager} needs to reconnect to the button and interpret its
 * notify-characteristic byte stream as press/release events.
 */
public final class BlePttBinding {
    @Getter @NonNull private final String mac;
    @Getter @NonNull private final String name;
    @Getter @NonNull private final UUID serviceUuid;
    @Getter @NonNull private final UUID characteristicUuid;
    /** Byte value (0..255) emitted on press. */
    @Getter private final int pressedValue;
    /** Byte value (0..255) emitted on release. */
    @Getter private final int releasedValue;

    public BlePttBinding(@NonNull String mac,
                         @NonNull String name,
                         @NonNull UUID serviceUuid,
                         @NonNull UUID characteristicUuid,
                         int pressedValue,
                         int releasedValue) {
        this.mac = mac;
        this.name = name;
        this.serviceUuid = serviceUuid;
        this.characteristicUuid = characteristicUuid;
        this.pressedValue = pressedValue & 0xFF;
        this.releasedValue = releasedValue & 0xFF;
    }

    /**
     * Returns true if the given first-byte value should be interpreted as "button pressed".
     * Uses an exact match against {@link #pressedValue}; if the device emitted neither the
     * stored pressed nor released value (e.g. multi-byte protocol), we fall back to a
     * non-zero rule for press.
     */
    public boolean isPressed(int firstByte) {
        int b = firstByte & 0xFF;
        if (b == pressedValue) return true;
        if (b == releasedValue) return false;
        // Fall back: treat any non-zero as pressed unless the binding inverted that mapping.
        return pressedValue != 0 ? b != 0 : b == 0;
    }

    /** Encodes the binding as a single string suitable for storage in AppSetting. */
    @NonNull
    public String serialize() {
        return mac + "|" + escape(name) + "|" + serviceUuid + "|" + characteristicUuid
                + "|" + pressedValue + "|" + releasedValue;
    }

    @Nullable
    public static BlePttBinding deserialize(@Nullable String s) {
        if (s == null || s.isEmpty()) return null;
        String[] parts = s.split("\\|", -1);
        if (parts.length < 6) return null;
        try {
            return new BlePttBinding(
                    parts[0],
                    unescape(parts[1]),
                    UUID.fromString(parts[2]),
                    UUID.fromString(parts[3]),
                    Integer.parseInt(parts[4]),
                    Integer.parseInt(parts[5]));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("|", "\\p");
    }

    private static String unescape(String s) {
        return s.replace("\\p", "|").replace("\\\\", "\\");
    }
}
