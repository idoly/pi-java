package io.github.idoly.pi.core.internal;

import com.fasterxml.jackson.databind.JavaType;
import io.github.idoly.pi.core.tool.PiTool;
import io.github.idoly.pi.core.tool.PiToolParameter;
import io.github.idoly.pi.core.tool.PiToolRegistrationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;

final class PiToolDefinition {
    private static final Set<Class<?>> INTEGER_TYPES = Set.of(
            byte.class, short.class, int.class, long.class,
            Byte.class, Short.class, Integer.class, Long.class);
    private static final Set<Class<?>> NUMBER_TYPES = Set.of(
            float.class, double.class, Float.class, Double.class);

    private final Object target;
    private final Method method;
    private final String name;
    private final String label;
    private final String description;
    private final Binding[] bindings;
    private final ObjectNode schema;

    private PiToolDefinition(
            Object target,
            Method method,
            PiTool tool,
            Binding[] bindings,
            ObjectNode schema
    ) {
        this.target = target;
        this.method = method;
        name = tool.name();
        label = tool.label().isBlank() ? tool.name() : tool.label();
        description = tool.description();
        this.bindings = bindings;
        this.schema = schema;
    }

    static PiToolDefinition create(Object target, Method method, ObjectMapper mapper) {
        PiTool tool = method.getAnnotation(PiTool.class);
        if (!Modifier.isPublic(method.getModifiers()) || Modifier.isStatic(method.getModifiers())) {
            throw new PiToolRegistrationException("@PiTool method must be public and non-static: " + method);
        }
        if (!method.canAccess(target) && !method.trySetAccessible()) {
            throw new PiToolRegistrationException("@PiTool method is not accessible: " + method);
        }
        if (!tool.name().matches("[A-Za-z][A-Za-z0-9_-]*")) {
            throw new PiToolRegistrationException("Invalid Tool name: " + tool.name());
        }
        if (tool.description().isBlank()) {
            throw new PiToolRegistrationException(
                    "Tool description must not be blank: " + tool.name());
        }
        if (CompletionStage.class.isAssignableFrom(method.getReturnType())) {
            throw new PiToolRegistrationException(
                    "Asynchronous @PiTool return types are not supported: " + method);
        }

        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = schema.putArray("required");
        schema.put("additionalProperties", false);
        Parameter[] parameters = method.getParameters();
        Binding[] bindings = new Binding[parameters.length];
        Set<String> names = new LinkedHashSet<>();

        for (int index = 0; index < parameters.length; index++) {
            Parameter parameter = parameters[index];
            PiToolParameter option = parameter.getAnnotation(PiToolParameter.class);
            String name = option != null && !option.name().isBlank()
                    ? option.name()
                    : parameter.isNamePresent() ? parameter.getName() : null;
            if (name == null || !name.matches("[A-Za-z][A-Za-z0-9_-]*")) {
                throw new PiToolRegistrationException(
                        "Tool parameter requires a valid @PiToolParameter name: " + method);
            }
            if (!names.add(name)) {
                throw new PiToolRegistrationException(
                        "Duplicate Tool parameter '" + name + "': " + method);
            }
            ObjectNode property = schemaFor(
                    parameter.getParameterizedType(), mapper, new LinkedHashSet<>());
            if (option != null && !option.description().isBlank()) {
                property.put("description", option.description());
            }
            properties.set(name, property);
            boolean isRequired = option == null || option.required();
            if (!isRequired && parameter.getType().isPrimitive()) {
                throw new PiToolRegistrationException(
                        "Optional Tool parameter must not use a primitive type: " + name);
            }
            if (isRequired) {
                required.add(name);
            }
            bindings[index] = new Binding(
                    name, parameter.getParameterizedType(), isRequired);
        }
        return new PiToolDefinition(
                target, method, tool, bindings, schema);
    }

    String name() {
        return name;
    }

    String label() {
        return label;
    }

    String description() {
        return description;
    }

    ObjectNode schema() {
        return schema.deepCopy();
    }

    JsonNode invoke(JsonNode arguments, ObjectMapper mapper) throws Exception {
        if (arguments == null || !arguments.isObject()) {
            throw new PiToolCallException("Tool arguments must be a JSON object");
        }
        Set<String> known = new LinkedHashSet<>();
        for (Binding binding : bindings) {
            known.add(binding.name());
        }
        arguments.fieldNames().forEachRemaining(name -> {
            if (!known.contains(name)) {
                throw new PiToolCallException("Unknown argument: " + name);
            }
        });

        Object[] values = new Object[bindings.length];
        for (int index = 0; index < bindings.length; index++) {
            Binding binding = bindings[index];
            JsonNode node = arguments.get(binding.name());
            if (node == null || node.isNull()) {
                if (binding.required()) {
                    throw new PiToolCallException(
                            "Missing required argument: " + binding.name());
                }
                values[index] = null;
                continue;
            }
            try {
                JavaType targetType = mapper.getTypeFactory().constructType(binding.type());
                values[index] = mapper.convertValue(node, targetType);
            } catch (IllegalArgumentException cause) {
                throw new PiToolCallException(
                        "Invalid argument '" + binding.name() + "': " + cause.getMessage(), cause);
            }
        }
        try {
            Object result = method.invoke(target, values);
            return result == null ? mapper.nullNode() : mapper.valueToTree(result);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw failure;
        }
    }

    private static ObjectNode schemaFor(Type type, ObjectMapper mapper, Set<Type> typePath) {
        if (!typePath.add(type)) {
            throw new PiToolRegistrationException("Recursive Tool parameter type is not supported: " + type);
        }
        try {
            if (type instanceof Class<?> rawType) {
                if (rawType == String.class || rawType == char.class || rawType == Character.class) {
                    return typeSchema(mapper, "string");
                }
                if (rawType == boolean.class || rawType == Boolean.class) {
                    return typeSchema(mapper, "boolean");
                }
                if (INTEGER_TYPES.contains(rawType)) {
                    return typeSchema(mapper, "integer");
                }
                if (NUMBER_TYPES.contains(rawType) || Number.class == rawType) {
                    return typeSchema(mapper, "number");
                }
                if (rawType.isEnum()) {
                    ObjectNode schema = typeSchema(mapper, "string");
                    ArrayNode enumValues = schema.putArray("enum");
                    for (Object constant : rawType.getEnumConstants()) {
                        enumValues.add(((Enum<?>) constant).name());
                    }
                    return schema;
                }
                if (rawType.isArray()) {
                    ObjectNode schema = typeSchema(mapper, "array");
                    schema.set("items", schemaFor(rawType.getComponentType(), mapper, typePath));
                    return schema;
                }
                if (rawType.isRecord()) {
                    return recordSchema(rawType, mapper, typePath);
                }
                throw new PiToolRegistrationException(
                        "Unsupported Tool parameter type: " + rawType.getTypeName());
            }
            if (type instanceof ParameterizedType parameterizedType) {
                if (!(parameterizedType.getRawType() instanceof Class<?> rawType)) {
                    throw new PiToolRegistrationException("Unsupported Tool parameter type: " + type);
                }
                Type[] typeArguments = parameterizedType.getActualTypeArguments();
                if (Collection.class.isAssignableFrom(rawType)) {
                    requireArity(type, typeArguments, 1);
                    ObjectNode schema = typeSchema(mapper, "array");
                    schema.set("items", schemaFor(typeArguments[0], mapper, typePath));
                    return schema;
                }
                if (Map.class.isAssignableFrom(rawType)) {
                    requireArity(type, typeArguments, 2);
                    if (typeArguments[0] != String.class) {
                        throw new PiToolRegistrationException(
                                "Tool Map key type must be String: " + type);
                    }
                    ObjectNode schema = typeSchema(mapper, "object");
                    schema.set("additionalProperties", schemaFor(typeArguments[1], mapper, typePath));
                    return schema;
                }
                throw new PiToolRegistrationException(
                        "Parameterized Tool DTO types are not supported: " + type);
            }
            if (type instanceof GenericArrayType genericArrayType) {
                ObjectNode schema = typeSchema(mapper, "array");
                schema.set("items", schemaFor(
                        genericArrayType.getGenericComponentType(), mapper, typePath));
                return schema;
            }
            if (type instanceof WildcardType || type instanceof TypeVariable<?>) {
                throw new PiToolRegistrationException(
                        "Wildcard and type-variable Tool parameters are not supported: " + type);
            }
            throw new PiToolRegistrationException("Unsupported Tool parameter type: " + type);
        } finally {
            typePath.remove(type);
        }
    }

    private static ObjectNode recordSchema(
            Class<?> recordType,
            ObjectMapper mapper,
            Set<Type> typePath
    ) {
        ObjectNode schema = typeSchema(mapper, "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = schema.putArray("required");
        schema.put("additionalProperties", false);
        for (RecordComponent component : recordType.getRecordComponents()) {
            ObjectNode propertySchema = schemaFor(component.getGenericType(), mapper, typePath);
            PiToolParameter componentMetadata = component.getAnnotation(PiToolParameter.class);
            String propertyName = componentMetadata != null && !componentMetadata.name().isBlank()
                    ? componentMetadata.name() : component.getName();
            if (componentMetadata != null && !componentMetadata.description().isBlank()) {
                propertySchema.put("description", componentMetadata.description());
            }
            properties.set(propertyName, propertySchema);
            if (componentMetadata == null || componentMetadata.required()) {
                required.add(propertyName);
            }
        }
        return schema;
    }

    private static void requireArity(
            Type type,
            Type[] typeArguments,
            int expectedCount
    ) {
        if (typeArguments.length != expectedCount) {
            throw new PiToolRegistrationException("Unsupported Tool parameter type: " + type);
        }
    }

    private static ObjectNode typeSchema(ObjectMapper mapper, String schemaType) {
        return mapper.createObjectNode().put("type", schemaType);
    }

    private record Binding(String name, Type type, boolean required) {
    }
}
