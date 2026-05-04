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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Built-in profiles for known Bluetooth LE PTT buttons. A "profile" is just a service
 * UUID + characteristic UUID + the byte semantics. New buttons can be added by extending
 * {@link #PROFILES}; nothing else needs to change.
 *
 * The standard semantics for all profiles shipped today: a single notify characteristic
 * that emits {@code 0x01} on press and {@code 0x00} on release. Future entries can override
 * those bytes if a particular model uses different values.
 */
public final class BlePttProfiles {

    public static final int DEFAULT_PRESSED = 0x01;
    public static final int DEFAULT_RELEASED = 0x00;

    public static final class Profile {
        public final String displayName;
        public final UUID serviceUuid;
        public final UUID characteristicUuid;
        public final int pressedValue;
        public final int releasedValue;

        public Profile(String displayName, UUID serviceUuid, UUID characteristicUuid,
                       int pressedValue, int releasedValue) {
            this.displayName = displayName;
            this.serviceUuid = serviceUuid;
            this.characteristicUuid = characteristicUuid;
            this.pressedValue = pressedValue;
            this.releasedValue = releasedValue;
        }
    }

    public static final List<Profile> PROFILES = Collections.unmodifiableList(Arrays.asList(
            new Profile(
                    "Blu-PTT",
                    UUID.fromString("89a8591d-bb19-485b-9f59-58492bc33e24"),
                    UUID.fromString("894c8042-e841-461c-a5c9-5a73d25db08e"),
                    DEFAULT_PRESSED, DEFAULT_RELEASED),
            new Profile(
                    "PRYME PTT-Z / compatible",
                    UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb"),
                    UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb"),
                    DEFAULT_PRESSED, DEFAULT_RELEASED),
            new Profile(
                    "PRYME BTH hand mic (BLE part)",
                    UUID.fromString("28393551-5345-5209-fd67-45287c0bdac5"),
                    UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb"),
                    DEFAULT_PRESSED, DEFAULT_RELEASED)
    ));

    /** Standard utility services to ignore when searching for unknown PTT characteristics. */
    public static final List<UUID> IGNORED_SERVICES = Collections.unmodifiableList(Arrays.asList(
            UUID.fromString("00001800-0000-1000-8000-00805f9b34fb"), // Generic Access
            UUID.fromString("00001801-0000-1000-8000-00805f9b34fb"), // Generic Attribute
            UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb"), // Device Information
            UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")  // Battery
    ));

    /** Returns the matching profile, if any, for an advertised service UUID. */
    @NonNull
    public static Optional<Profile> matchByServiceUuid(@Nullable UUID advertisedService) {
        if (advertisedService == null) return Optional.empty();
        for (Profile p : PROFILES) {
            if (p.serviceUuid.equals(advertisedService)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    private BlePttProfiles() {}
}
