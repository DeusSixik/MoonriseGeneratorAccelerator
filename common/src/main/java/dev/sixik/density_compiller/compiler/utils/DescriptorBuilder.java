package dev.sixik.density_compiller.compiler.utils;

import java.util.Objects;

/**
 * A fluent builder for constructing JVM type descriptors and method descriptors.
 *
 * <p>This class provides a type-safe way to build JVM descriptors for primitive types,
 * object types, arrays, and complete method signatures. The builder pattern allows
 * for chaining method calls to create complex descriptors.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>
 * // Build a parameter descriptor: "ID[Ljava/lang/Object;"
 * String args = DescriptorBuilder.builder()
 *     .int_()
 *     .double_()
 *     .array(Object.class)
 *     .build();
 *
 * // Build a complete method descriptor: "(ID[Ljava/lang/Object;)V"
 * String methodDesc = DescriptorBuilder.builder()
 *     .int_()
 *     .double_()
 *     .array(Object.class)
 *     .buildMethod(void.class);
 * </pre>
 */
public final class DescriptorBuilder {

    /**
     * Private constructor to prevent instantiation.
     */
    private DescriptorBuilder() {
    }

    /**
     * Creates a new builder instance for constructing descriptors.
     *
     * @return a new {@code Builder} instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder class for constructing JVM descriptors.
     *
     * <p>This builder accumulates type descriptors and can produce either a parameter
     * descriptor or a complete method descriptor.</p>
     */
    public static final class Builder {
        private final StringBuilder params = new StringBuilder();

        /**
         * Appends the boolean primitive type descriptor ('Z') to the parameter list.
         *
         * @return this builder instance for method chaining
         */
        public Builder boolean_() {
            params.append('Z');
            return this;
        }

        /**
         * Appends the byte primitive type descriptor ('B') to the parameter list.
         *
         * @return this builder instance for method chaining
         */
        public Builder byte_() {
            params.append('B');
            return this;
        }

        /**
         * Appends the char primitive type descriptor ('C') to the parameter list.
         *
         * @return this builder instance for method chaining
         */
        public Builder char_() {
            params.append('C');
            return this;
        }

        /**
         * Appends the short primitive type descriptor ('S') to the parameter list.
         *
         * @return this builder instance for method chaining
         */
        public Builder short_() {
            params.append('S');
            return this;
        }

        /**
         * Appends the int primitive type descriptor ('I') to the parameter list.
         *
         * @return this builder instance for method chaining
         */
        public Builder int_() {
            params.append('I');
            return this;
        }

        /**
         * Appends the long primitive type descriptor ('J') to the parameter list.
         *
         * @return this builder instance for method chaining
         */
        public Builder long_() {
            params.append('J');
            return this;
        }

        /**
         * Appends the float primitive type descriptor ('F') to the parameter list.
         *
         * @return this builder instance for method chaining
         */
        public Builder float_() {
            params.append('F');
            return this;
        }

        /**
         * Appends the double primitive type descriptor ('D') to the parameter list.
         *
         * @return this builder instance for method chaining
         */
        public Builder double_() {
            params.append('D');
            return this;
        }

        /**
         * Single-letter alias for {@link #boolean_()}.
         *
         * @return this builder instance for method chaining
         */
        public Builder z() {
            return boolean_();
        }

        /**
         * Single-letter alias for {@link #byte_()}.
         *
         * @return this builder instance for method chaining
         */
        public Builder b() {
            return byte_();
        }

        /**
         * Single-letter alias for {@link #char_()}.
         *
         * @return this builder instance for method chaining
         */
        public Builder c() {
            return char_();
        }

        /**
         * Single-letter alias for {@link #short_()}.
         *
         * @return this builder instance for method chaining
         */
        public Builder s() {
            return short_();
        }

        /**
         * Single-letter alias for {@link #int_()}.
         *
         * @return this builder instance for method chaining
         */
        public Builder i() {
            return int_();
        }

        /**
         * Single-letter alias for {@link #long_()}.
         *
         * @return this builder instance for method chaining
         */
        public Builder j() {
            return long_();
        }

        /**
         * Single-letter alias for {@link #float_()}.
         *
         * @return this builder instance for method chaining
         */
        public Builder f() {
            return float_();
        }

        /**
         * Single-letter alias for {@link #double_()}.
         *
         * @return this builder instance for method chaining
         */
        public Builder d() {
            return double_();
        }

        /**
         * Appends a type descriptor for the given class.
         *
         * <p>The method handles primitive types, object types, and arrays,
         * generating the appropriate JVM descriptor.</p>
         *
         * @param clazz the class to generate a descriptor for
         * @return this builder instance for method chaining
         * @throws NullPointerException if {@code clazz} is null
         */
        public Builder type(Class<?> clazz) {
            params.append(descriptorOf(Objects.requireNonNull(clazz, "clazz")));
            return this;
        }

        /**
         * Appends a one-dimensional array descriptor for the given component type.
         *
         * <p>Examples:</p>
         * <ul>
         *   <li>{@code array(Object.class)} → {@code "[Ljava/lang/Object;"}</li>
         *   <li>{@code array(int.class)} → {@code "[I"}</li>
         * </ul>
         *
         * @param componentType the component type of the array
         * @return this builder instance for method chaining
         * @throws NullPointerException if {@code componentType} is null
         */
        public Builder array(Class<?> componentType) {
            return array(componentType, 1);
        }

        /**
         * Appends a multi-dimensional array descriptor for the given component type.
         *
         * <p>Examples:</p>
         * <ul>
         *   <li>{@code array(String.class, 2)} → {@code "[[Ljava/lang/String;"}</li>
         *   <li>{@code array(int.class, 3)} → {@code "[[[I"}</li>
         * </ul>
         *
         * @param componentType the component type of the array
         * @param dims the number of array dimensions (must be greater than 0)
         * @return this builder instance for method chaining
         * @throws NullPointerException if {@code componentType} is null
         * @throws IllegalArgumentException if {@code dims} is less than or equal to 0
         */
        public Builder array(Class<?> componentType, int dims) {
            Objects.requireNonNull(componentType, "componentType");
            if (dims <= 0) throw new IllegalArgumentException("dims must be > 0");

            for (int k = 0; k < dims; k++) params.append('[');
            params.append(componentDescriptorOf(componentType));
            return this;
        }

        /**
         * Appends an object type descriptor using the internal name (slash-separated).
         *
         * <p>Examples:</p>
         * <ul>
         *   <li>{@code object("java/util/List")} → {@code "Ljava/util/List;"}</li>
         *   <li>{@code object("java/lang/String")} → {@code "Ljava/lang/String;"}</li>
         * </ul>
         *
         * @param internalName the internal name of the class (e.g., "java/lang/Object")
         * @return this builder instance for method chaining
         * @throws NullPointerException if {@code internalName} is null
         */
        public Builder object(String internalName) {
            Objects.requireNonNull(internalName, "internalName");
            params.append('L').append(internalName).append(';');
            return this;
        }

        /**
         * Builds the concatenated parameter descriptor.
         *
         * <p>This returns only the parameter part without parentheses or return type.
         * Example: {@code "ID[Ljava/lang/Object;"}</p>
         *
         * @return the parameter descriptor as a string
         */
        public String build() {
            return params.toString();
        }

        /**
         * Builds a complete method descriptor including parentheses and return type.
         *
         * <p>Example: {@code "(ID[Ljava/lang/Object;)V"}</p>
         *
         * @param returnType the return type of the method
         * @return the complete method descriptor as a string
         * @throws NullPointerException if {@code returnType} is null
         */
        public String buildMethod(Class<?> returnType) {
            Objects.requireNonNull(returnType, "returnType");
            return "(" + params + ")" + descriptorOf(returnType);
        }

        public String buildMethodVoid() {
            return buildMethod(void.class);
        }
    }

    /**
     * Converts a {@code Class} object to its JVM type descriptor.
     *
     * <p>Handles primitive types, arrays, and object types according to JVM spec.</p>
     *
     * @param clazz the class to convert
     * @return the JVM type descriptor
     * @throws IllegalArgumentException if an unknown primitive type is encountered
     */
    private static String descriptorOf(Class<?> clazz) {
        if (clazz.isPrimitive()) {
            if (clazz == void.class) return "V";
            if (clazz == boolean.class) return "Z";
            if (clazz == byte.class) return "B";
            if (clazz == char.class) return "C";
            if (clazz == short.class) return "S";
            if (clazz == int.class) return "I";
            if (clazz == long.class) return "J";
            if (clazz == float.class) return "F";
            if (clazz == double.class) return "D";
            throw new IllegalArgumentException("Unknown primitive: " + clazz);
        }

        if (clazz.isArray()) {
            return clazz.getName().replace('.', '/');
        }

        return "L" + clazz.getName().replace('.', '/') + ";";
    }

    /**
     * Generates the descriptor for a component type in an array context.
     *
     * <p>For primitive types, returns the single-letter descriptor.
     * For object types, returns the "L...;" descriptor.
     * For array types, returns the full array descriptor.</p>
     *
     * @param componentType the component type of an array
     * @return the descriptor for the component type
     */
    private static String componentDescriptorOf(Class<?> componentType) {
        if (componentType.isPrimitive()) return descriptorOf(componentType);
        if (componentType.isArray()) return descriptorOf(componentType);
        return "L" + componentType.getName().replace('.', '/') + ";";
    }
}