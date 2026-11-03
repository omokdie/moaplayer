package androidx.annotation;
import java.lang.annotation.*;

@Retention(RetentionPolicy.CLASS)
public @interface Size { long value() default -1; long min() default Long.MIN_VALUE; long max() default Long.MAX_VALUE; long multiple() default 1; }
