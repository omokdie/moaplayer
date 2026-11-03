package androidx.annotation;
import java.lang.annotation.*;

@Retention(RetentionPolicy.CLASS)
@Repeatable(RequiresExtension.Container.class)
public @interface RequiresExtension { int extension(); int version(); @Retention(RetentionPolicy.CLASS) @interface Container { RequiresExtension[] value(); } }
