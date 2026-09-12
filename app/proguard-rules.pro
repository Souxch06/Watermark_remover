# Media3 Transformer / Effect rely on reflection for some codec helpers; keep them intact.
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
