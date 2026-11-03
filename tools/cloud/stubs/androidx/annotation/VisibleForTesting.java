package androidx.annotation;
import java.lang.annotation.*;

@Retention(RetentionPolicy.CLASS)
public @interface VisibleForTesting { int PRIVATE = 2; int PACKAGE_PRIVATE = 3; int PROTECTED = 4; int NONE = 5; int otherwise() default PRIVATE; }
