# ── smbj ───────────────────────────────────────────────
# smbj 通过反射装配协议包与事件总线，不保留会在运行时 ClassNotFound
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
-dontwarn com.hierynomus.**
-dontwarn net.engio.mbassy.**

# BouncyCastle：仅 SMB3 签名/加密路径用到，当前服务端两者均关闭
-dontwarn org.bouncycastle.**
-keep class org.bouncycastle.jcajce.provider.** { *; }

# slf4j
-dontwarn org.slf4j.**

# ── NanoHTTPD ──────────────────────────────────────────
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**

# ── Media3 ─────────────────────────────────────────────
-dontwarn androidx.media3.**

# ── kotlinx.serialization ──────────────────────────────
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.lanplay.player.**$$serializer { *; }
-keepclassmembers class com.lanplay.player.** { *** Companion; }
-keepclasseswithmembers class com.lanplay.player.** { kotlinx.serialization.KSerializer serializer(...); }
