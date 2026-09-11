# Proguard rules for TwinBooks
-keep class org.ecos.logic.twinbooks.data.** { *; }
-keep class io.documentnode.epub4j.** { *; }
-dontwarn io.documentnode.epub4j.**

# epub4j/kxml2 xmlpull conflict with Android platform
-dontwarn org.kxml2.**
-keep class org.kxml2.io.** { *; }
-dontwarn org.xmlpull.v1.**
-keep class org.xmlpull.v1.** { *; }
