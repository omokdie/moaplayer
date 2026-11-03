package androidx.annotation;
import java.lang.annotation.*;

@Retention(RetentionPolicy.CLASS)
public @interface OptIn { Class<? extends Annotation>[] markerClass(); }
