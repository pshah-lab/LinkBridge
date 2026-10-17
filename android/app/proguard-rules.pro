# Android discovers the BouncyCastle TLS implementation through concrete classes
# and interfaces during the PSK handshake. Keep the provider and TLS classes.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# The Android framework instantiates these exported components from the manifest.
-keep public class com.linkbridge.android.MainActivity { public <init>(); }
-keep public class com.linkbridge.android.ShareActivity { public <init>(); }
-keep public class com.linkbridge.android.LinkBridgeService { public <init>(); }

# JSON and NSD payloads use these data types by their property names.
-keep class com.linkbridge.android.DeviceIdentity { *; }
-keep class com.linkbridge.android.TrustedPeer { *; }
