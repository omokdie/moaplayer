package javax.annotation;
import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
public @interface CheckReturnValue { javax.annotation.meta.When when() default javax.annotation.meta.When.ALWAYS; }
