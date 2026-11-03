package androidx.annotation;
import java.lang.annotation.*;

@Retention(RetentionPolicy.CLASS)
public @interface RequiresPermission { String value() default ""; String[] allOf() default {}; String[] anyOf() default {}; boolean conditional() default false; @interface Read { RequiresPermission value() default @RequiresPermission; } @interface Write { RequiresPermission value() default @RequiresPermission; } }
