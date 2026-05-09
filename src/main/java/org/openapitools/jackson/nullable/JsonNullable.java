package org.openapitools.jackson.nullable;

import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Minimal JsonNullable implementation compatible with Jackson 3 / Spring Boot 4.
 *
 * <p>Used to distinguish an undefined patch field from an explicitly provided null.</p>
 *
 * @param <T> wrapped value type
 */
public final class JsonNullable<T> {

    private static final JsonNullable<?> UNDEFINED = new JsonNullable<>(false, null);

    private final boolean defined;
    private final T value;

    private JsonNullable(boolean defined, T value) {
        this.defined = defined;
        this.value = value;
    }

    @SuppressWarnings("unchecked")
    public static <T> JsonNullable<T> undefined() {
        return (JsonNullable<T>) UNDEFINED;
    }

    public static <T> JsonNullable<T> of(T value) {
        return new JsonNullable<>(true, value);
    }

    public boolean isDefined() {
        return defined;
    }

    public boolean isPresent() {
        return defined && value != null;
    }

    public T get() {
        if (!defined) {
            throw new NoSuchElementException("JsonNullable is undefined");
        }
        return value;
    }

    public T orElse(T other) {
        return defined ? value : other;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof JsonNullable<?> other)) {
            return false;
        }
        return defined == other.defined && Objects.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(defined, value);
    }

    @Override
    public String toString() {
        if (!defined) {
            return "JsonNullable.undefined";
        }
        return "JsonNullable[" + value + "]";
    }
}
