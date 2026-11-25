# Keep WebRTC & Chromium jni_zero JNI bridges intact under R8 release shrinking
-keep class org.webrtc.** { *; }
-keep class org.jni_zero.** { *; }
-keep class J.N { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keepclassmembers class * {
    @org.jni_zero.CalledByNative <methods>;
    @org.jni_zero.CalledByNativeUnchecked <methods>;
    @org.webrtc.CalledByNative <methods>;
    @org.webrtc.CalledByNativeUnchecked <methods>;
}
-dontwarn org.webrtc.**
-dontwarn org.jni_zero.**
