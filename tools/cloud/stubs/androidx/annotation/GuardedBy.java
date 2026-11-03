package androidx.annotation;
import java.lang.annotation.*;

@Retention(RetentionPolicy.CLASS)
public @interface GuardedBy { String value(); }
