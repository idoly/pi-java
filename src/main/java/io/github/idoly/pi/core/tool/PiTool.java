package io.github.idoly.pi.core.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Exposes a synchronous public instance method as a pi Tool.
 *
 * <p>Tool methods may be called concurrently. Aborting an activity does not forcibly interrupt
 * a Java method that has already started, so implementations must apply their own downstream
 * timeouts and cancellation policy.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface PiTool {
    /** Returns the protocol Tool name. */
    String name();

    /** Returns the description supplied to the model. */
    String description();

    /** Returns an optional display label. */
    String label() default "";
}
