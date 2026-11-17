-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep JNI callback classes invoked from native whisper.cpp / llama.cpp
-keep class app.hushcast.automute.nativebridge.** { *; }

# Keep kotlinx.serialization serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep Room entities/DAOs
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**