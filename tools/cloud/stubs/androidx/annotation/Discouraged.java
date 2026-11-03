package androidx.annotation;
import java.lang.annotation.*;

@Retention(RetentionPolicy.CLASS)
public @interface Discouraged { String message(); }
