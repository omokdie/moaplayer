package androidx.annotation;
import java.lang.annotation.*;

@Retention(RetentionPolicy.CLASS)
public @interface Dimension { int DP = 0; int PX = 1; int SP = 2; int unit() default PX; }
