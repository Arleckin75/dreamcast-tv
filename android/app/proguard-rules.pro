# LibVLC JNI_OnLoad looks up classes, fields and callback methods by their
# original names. Native-only references are invisible to R8.
-keep class org.videolan.libvlc.** { *; }
