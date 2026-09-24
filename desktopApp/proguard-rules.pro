# Piko 桌面端 release 的 ProGuard 规则。只做裁剪，不混淆：体积主要来自没用到的图标与
# Compose 组件，混淆只再省几个百分点，却让崩溃栈难读、反射查找更易出错。
-dontobfuscate

# JNA 按接口方法名反射绑定 native 函数，结构体按字段名映射内存布局。
# mediamp 用它调 kernel32 的 SetDllDirectoryW，mpv 绑定也走 JNA
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.Library { *; }
-keep class * implements com.sun.jna.Callback { *; }
-keep class * extends com.sun.jna.Structure { *; }
-dontwarn com.sun.jna.**

# mediamp：mpv 的 JNI 回调按类名与方法签名从 native 侧查找，播放器工厂与画面表面经 ServiceLoader 装载
-keep class org.openani.mediamp.** { *; }
-dontwarn org.openani.mediamp.**

# ServiceLoader 装载的实现类。ProGuard 不像 R8 那样自动保留 META-INF/services 里列出的类
-keep class coil3.network.okhttp.internal.OkHttpNetworkFetcherServiceLoaderTarget { *; }
-keep class io.ktor.client.engine.okhttp.OkHttpEngineContainer { *; }
-keep class io.ktor.serialization.kotlinx.json.KotlinxSerializationJsonExtensionProvider { *; }
-keep class org.slf4j.simple.SimpleServiceProvider { *; }

# kotlinx.serialization：生成的 serializer 经伴生对象的 serializer() 查找
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class **$$serializer { *; }

# Ktor 与 OkHttp 引用了桌面 JVM 上不存在的可选依赖
-dontwarn io.ktor.**
-dontwarn okhttp3.internal.platform.**
-dontwarn okhttp3.internal.graal.**
-dontwarn org.graalvm.**
-dontwarn com.oracle.svm.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.slf4j.**
-dontwarn kotlinx.coroutines.debug.**
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.jetbrains.annotations.**

# isoparser 按类名反射创建 MP4 box 实现
-keep class com.coremedia.iso.** { *; }
-keep class com.googlecode.mp4parser.** { *; }
-keep class org.mp4parser.** { *; }
-dontwarn com.coremedia.iso.**
-dontwarn com.googlecode.mp4parser.**
-dontwarn org.aspectj.**
