/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Proprietary FingerDance component. Not licensed under the MPL 2.0 that
 * covers the surrounding Wolvic files.
 */
package com.thanford.fingerdance.settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

public final class Binding {
    public final int action;
    @Nullable public final String param;

    public Binding(int action, @Nullable String param) {
        this.action = action;
        this.param = param;
    }

    @NonNull
    public static Binding of(int action) {
        return new Binding(action, null);
    }

    @NonNull
    public static Binding of(int action, @Nullable String param) {
        return new Binding(action, param);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Binding)) return false;
        Binding other = (Binding) o;
        return action == other.action && Objects.equals(param, other.param);
    }

    @Override
    public int hashCode() {
        return Objects.hash(action, param);
    }

    @Override
    public String toString() {
        return param == null
                ? "Binding{action=" + action + "}"
                : "Binding{action=" + action + ", param=" + param + "}";
    }
}
