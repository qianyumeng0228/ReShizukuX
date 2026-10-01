# KotlinX Serialization — keep all @Serializable classes and serializers
-keepattributes *Annotation*, InnerClasses, EnclosingMethod
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep RepoManifest and all @Serializable data classes in :modules
-keep class io.reshizukux.modules.repository.RepoManifest { *; }
-keep class io.reshizukux.modules.repository.RepoManifest$* { *; }
-keepclasseswithmembers class io.reshizukux.modules.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room entities
-keep class io.reshizukux.modules.db.** { *; }
