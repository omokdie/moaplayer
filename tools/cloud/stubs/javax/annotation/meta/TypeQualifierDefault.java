package javax.annotation.meta;
import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
public @interface TypeQualifierDefault { ElementType[] value() default {}; }
