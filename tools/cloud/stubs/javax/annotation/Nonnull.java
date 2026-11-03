package javax.annotation;
import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
public @interface Nonnull { javax.annotation.meta.When when() default javax.annotation.meta.When.ALWAYS; }
