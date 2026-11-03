package androidx.annotation;
import java.lang.annotation.*;

@Retention(RetentionPolicy.CLASS)
public @interface DeprecatedSinceApi { int api(); String message() default ""; }
