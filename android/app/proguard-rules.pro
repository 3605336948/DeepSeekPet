# 本工程不启用混淆（isMinifyEnabled = false）。保留规则仅作占位。
-keep class com.dshpet.mobile.** { *; }
# addJavascriptInterface 的桥接对象：被 WebView 反射调用，任何混淆/裁剪都会断链
-keepclassmembers class com.dshpet.mobile.PetBridge {
    @android.webkit.JavascriptInterface <methods>;
}
