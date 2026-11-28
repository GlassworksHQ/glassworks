# libwebrtc's native code calls its Java classes (and their members) by name through JNI. The prefixed build keeps
# them under livekit.org (webrtc-sdk/android's android-prefixed artifact).
-keep class livekit.org.webrtc.** { *; }
-keep class livekit.org.jni_zero.** { *; }
-dontwarn livekit.org.webrtc.**
