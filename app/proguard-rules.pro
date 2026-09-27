
-keepattributes RuntimeVisibleAnnotations, AnnotationDefault
-keepattributes InnerClasses, Signature, EnclosingMethod, Exceptions

# --- kotlinx.serialization ------------------------------------------------

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

-keepclassmembers class **$$serializer {
    *** descriptor;
}
-keep,includedescriptorclasses class **$$serializer { *; }

-keepclassmembers @kotlinx.serialization.Serializable class * {
    <fields>;
}

-keep class kotlin.Metadata { *; }

# --- Crashlytics ----------------------------------------------------------
-keepnames class com.filmax.core.domain.error.**
