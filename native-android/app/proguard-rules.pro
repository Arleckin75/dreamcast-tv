# LibVLC invokes these bindings from JNI. Keep names and native callback methods.
-keep class org.videolan.libvlc.** { *; }
-keep class org.videolan.medialibrary.** { *; }
# WorkManager persists the worker class name across application updates.
-keep class com.dreamcast.nativeapp.EpisodeWorker { *; }
