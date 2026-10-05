# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Jsoup
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# LiteRT-LM (native JNI callbacks need class/member names intact)
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**
