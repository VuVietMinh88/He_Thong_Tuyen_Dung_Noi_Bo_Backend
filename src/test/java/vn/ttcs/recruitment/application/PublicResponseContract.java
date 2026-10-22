package vn.ttcs.recruitment.application;

import tools.jackson.databind.JsonNode;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 341 (story S4-05, "Không tiết lộ nhận xét nội bộ hay tên người phỏng vấn"): the checks every response a
 * candidate (and, from task 352, a referrer) receives shares. Such a response is a record whose components are a
 * whitelist; these checks pin the whitelist and refuse a component that looks internal, so a later field (a Sprint 5
 * note, a Sprint 6 interviewer) cannot reach a public body unnoticed.
 */
public final class PublicResponseContract {
    // Names of internal data. "tracking" is here because the tracking code is a secret: only the candidate's own
    // receipt (the intake's 201) may carry it, never a referrer's view (task 352).
    private static final Pattern INTERNAL_NAME = Pattern.compile("(?i).*(note|comment|evaluat|score|rating|interview"
            + "|recruiter|manager|staff|actor|requisition|reason|email|phone|cv|file|stage|source|referr|hash|tracking"
            + "|candidate|applicationid|postingid).*");

    private PublicResponseContract() {}

    /** The record has exactly these components, in this order. */
    public static void assertExactComponents(Class<? extends Record> type, String... names) {
        assertThat(Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList())
                .as("components of %s", type.getSimpleName()).containsExactly(names);
    }

    /**
     * No component of the record, or of a record it contains (directly, in a collection, an optional or an array),
     * looks internal: a name matching INTERNAL_NAME, an identifier (named "id" or "...Id", or of type UUID)
     * or a type mentioning {@link ApplicationStage}. An allowed exception is a component path ("applicationId",
     * "actions.canWithdraw") that would otherwise be refused; an exception that names no such component fails too,
     * so the list cannot outlive the component it was written for.
     */
    public static void assertNoInternalComponents(Class<? extends Record> type, String... allowedExceptions) {
        Set<String> allowed = new LinkedHashSet<>(List.of(allowedExceptions));
        List<String> internal = new ArrayList<>();
        inspect(type, "", internal, new LinkedHashSet<>());
        assertThat(internal.stream().filter(path -> !allowed.contains(path)).toList())
                .as("internal-looking components of %s", type.getSimpleName()).isEmpty();
        assertThat(allowed.stream().filter(path -> !internal.contains(path)).toList())
                .as("allowed exceptions of %s that name no internal-looking component", type.getSimpleName()).isEmpty();
    }

    /** The JSON object has exactly these keys (in any order). */
    public static void assertJsonKeys(JsonNode node, String... keys) {
        assertThat(node.isObject()).as("a JSON object: %s", node).isTrue();
        assertThat(node.propertyNames()).as("keys of %s", node).containsExactlyInAnyOrder(keys);
    }

    private static void inspect(Class<?> type, String prefix, List<String> internal, Set<Class<?>> path) {
        if (!path.add(type)) return;                                  // a recursive record is inspected once per path
        for (RecordComponent component : type.getRecordComponents()) {
            String name = prefix + component.getName();
            List<Class<?>> classes = new ArrayList<>();
            collect(component.getGenericType(), classes, new LinkedHashSet<>());
            if (looksInternal(component.getName()) || classes.contains(ApplicationStage.class)
                    || classes.contains(UUID.class)) {
                internal.add(name);
            }
            for (Class<?> nested : classes) {
                if (nested.isRecord()) inspect(nested, name + ".", internal, path);
            }
        }
        path.remove(type);
    }

    private static boolean looksInternal(String name) {
        return INTERNAL_NAME.matcher(name).matches() || name.equals("id") || name.endsWith("Id") || name.endsWith("ID");
    }

    // Every class a component type names: the type itself, its type arguments, array elements, wildcard and type
    // variable bounds (List<Entry>, Optional<ApplicationStage>, Map<String, Entry>, Entry[]).
    private static void collect(Type type, List<Class<?>> classes, Set<Type> seen) {
        if (!seen.add(type)) return;                                  // T extends Comparable<T> refers to itself
        switch (type) {
            case Class<?> plain when plain.isArray() -> collect(plain.getComponentType(), classes, seen);
            case Class<?> plain -> classes.add(plain);
            case ParameterizedType parameterized -> {
                collect(parameterized.getRawType(), classes, seen);
                for (Type argument : parameterized.getActualTypeArguments()) collect(argument, classes, seen);
            }
            case GenericArrayType array -> collect(array.getGenericComponentType(), classes, seen);
            case WildcardType wildcard -> {
                for (Type bound : wildcard.getUpperBounds()) collect(bound, classes, seen);
                for (Type bound : wildcard.getLowerBounds()) collect(bound, classes, seen);
            }
            case TypeVariable<?> variable -> {
                for (Type bound : variable.getBounds()) collect(bound, classes, seen);
            }
            default -> throw new IllegalArgumentException("Unsupported component type " + type);
        }
    }
}
