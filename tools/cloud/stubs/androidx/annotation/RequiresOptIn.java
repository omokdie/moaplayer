package androidx.annotation;
import java.lang.annotation.*;

@Retention(RetentionPolicy.CLASS)
@Target(ElementType.ANNOTATION_TYPE)
public @interface RequiresOptIn { Level level() default Level.ERROR; enum Level { WARNING, ERROR } }
