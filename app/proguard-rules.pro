# Music Sheet Flow ProGuard Rules

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# Keep Room entities
-keep class net.tigr.musicsheetflow.data.entity.** { *; }

# Keep data classes used with Room
-keepclassmembers class * {
    @androidx.room.* <fields>;
}
