package javax.annotation.meta;
import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
public @interface TypeQualifier { Class<?> applicableTo() default Object.class; }
