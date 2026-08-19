package io.github.idoly.pi.core.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Describes a Tool method parameter or record component for JSON Schema generation. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.FIELD})
public @interface PiToolParameter {
    /** Returns the JSON property name, or an empty string to use Java parameter metadata. */
    String name() default "";

    /** Returns an optional model-facing description. */
    String description() default "";

    /** Returns whether the top-level argument is required. */
    boolean required() default true;
}
