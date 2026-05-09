package org.openapitools.jackson.nullable;

import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.BeanProperty;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;

/**
 * Jackson 3 module for JsonNullable.
 */
public class JsonNullableModule extends SimpleModule {

    public JsonNullableModule() {
        addDeserializer((Class<JsonNullable<?>>) (Class<?>) JsonNullable.class, new JsonNullableDeserializer());
        addSerializer((Class<JsonNullable<?>>) (Class<?>) JsonNullable.class, new JsonNullableSerializer());
    }

    private static final class JsonNullableDeserializer extends ValueDeserializer<JsonNullable<?>> {

        private final JavaType valueType;

        private JsonNullableDeserializer() {
            this(null);
        }

        private JsonNullableDeserializer(JavaType valueType) {
            this.valueType = valueType;
        }

        @Override
        public JsonNullable<?> deserialize(JsonParser parser, DeserializationContext context) {
            Object value = valueType == null
                    ? context.readValue(parser, Object.class)
                    : context.readValue(parser, valueType);
            return JsonNullable.of(value);
        }

        @Override
        public JsonNullable<?> getNullValue(DeserializationContext context) {
            return JsonNullable.of(null);
        }

        @Override
        public ValueDeserializer<?> createContextual(DeserializationContext context, BeanProperty property) {
            JavaType contextualType = property != null ? property.getType() : context.getContextualType();
            JavaType wrappedType = contextualType != null && contextualType.containedTypeCount() == 1
                    ? contextualType.containedType(0)
                    : context.constructType(Object.class);
            return new JsonNullableDeserializer(wrappedType);
        }
    }

    private static final class JsonNullableSerializer extends ValueSerializer<JsonNullable<?>> {

        private final ValueSerializer<Object> valueSerializer;

        private JsonNullableSerializer() {
            this(null);
        }

        private JsonNullableSerializer(ValueSerializer<Object> valueSerializer) {
            this.valueSerializer = valueSerializer;
        }

        @Override
        public void serialize(JsonNullable<?> value, JsonGenerator generator, SerializationContext serializers) {
            if (value == null || !value.isDefined()) {
                serializers.defaultSerializeNullValue(generator);
                return;
            }

            Object actualValue = value.orElse(null);
            if (actualValue == null) {
                serializers.defaultSerializeNullValue(generator);
                return;
            }

            if (valueSerializer != null) {
                valueSerializer.serialize(actualValue, generator, serializers);
                return;
            }

            serializers.writeValue(generator, actualValue);
        }

        @Override
        public boolean isEmpty(SerializationContext provider, JsonNullable<?> value) {
            return value == null || !value.isDefined() || value.orElse(null) == null;
        }

        @Override
        @SuppressWarnings("unchecked")
        public ValueSerializer<?> createContextual(SerializationContext provider, BeanProperty property) {
            if (property == null || property.getType().containedTypeCount() != 1) {
                return this;
            }

            JavaType wrappedType = property.getType().containedType(0);
            ValueSerializer<Object> serializer = provider.findValueSerializer(wrappedType);
            return new JsonNullableSerializer(serializer);
        }
    }
}
