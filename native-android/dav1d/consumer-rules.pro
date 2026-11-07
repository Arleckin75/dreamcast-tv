# Native entry points and classes referenced by dav1d JNI.
-keep class androidx.media3.decoder.av1.** { *; }

# dav1d_jni.cc looks up these shared Media3 buffers and members by name.
-keep class androidx.media3.decoder.DecoderInputBuffer {
    java.nio.ByteBuffer data;
}
-keep class androidx.media3.decoder.VideoDecoderOutputBuffer {
    int width;
    int height;
    int[] yuvStrides;
    int yStride;
    int uvStride;
    java.nio.ByteBuffer data;
    long decoderPrivate;
    void init(long, int, java.nio.ByteBuffer);
    boolean initForYuvFrame(int, int, int, int, int);
    void initForPrivateFrame(int, int);
    boolean initForOffsetFrames(int, int, int, int, int, int, int);
}
-keepclassmembers class androidx.media3.decoder.Buffer {
    void setFlags(int);
}
