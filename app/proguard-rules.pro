# ── Compose / miuix：反射与运行时要保留 ─────────────────────
-keep class top.yukonga.miuix.** { *; }
-keep class androidx.compose.runtime.** { *; }
-keepclassmembers class ** {
    @androidx.compose.runtime.Composable <methods>;
}
-dontwarn androidx.compose.**
-dontwarn top.yukonga.**

# ── 不混淆数据模型（JSON 解析 + 状态类）────────────────────
-keep class com.b50.roast.data.** { *; }
-keep class com.b50.roast.AppState { *; }

# ── Android 基础 ────────────────────────────────────────────
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keepclassmembers class * { public <init>(...); }

# ── 去掉日志与行号，进一步减小体积 ──────────────────────────
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
}
