# Keep the network libraries whole: they use reflection / service loading / annotations.
-keep class com.hierynomus.** { *; }
-keep class com.rapid7.** { *; }
-keep class net.engio.mbassador.** { *; }
-keep class org.apache.commons.net.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class org.slf4j.** { *; }
-keep class javax.** { *; }
-dontwarn com.hierynomus.**
-dontwarn com.rapid7.**
-dontwarn net.engio.**
-dontwarn org.apache.commons.net.**
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
-dontwarn javax.**
-dontwarn java.lang.invoke.**
-dontwarn org.ietf.jgss.**
-dontwarn sun.security.**

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
