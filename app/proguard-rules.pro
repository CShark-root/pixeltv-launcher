# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.pixelden.launcher.**$$serializer { *; }
-keepclassmembers class com.pixelden.launcher.** {
    *** Companion;
}
-keepclasseswithmembers class com.pixelden.launcher.** {
    kotlinx.serialization.KSerializer serializer(...);
}
