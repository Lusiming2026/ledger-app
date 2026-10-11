# ============================================================================
# R8 / ProGuard 规则（release 构建）
# ============================================================================
# 本工程用了三类「靠反射/序列化/动态加载」的库，必须显式 keep，否则 release
# 包会在运行期炸而不是在编译期报错——这是最容易漏的一环。

# ---------------------------------------------------------------- kotlinx.serialization
# 备份 payload（LedgerSnapshot / Account / Category / ...）靠生成的
# `$serializer` 单例做 JSON 编解码，混淆后名字对不上就会 NoSuchMethodError。
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# 领域模型 + 备份传输对象：保留其生成的 serializer 与 companion
-keep,includedescriptorclasses class com.ledger.domain.model.**$$serializer { *; }
-keepclassmembers class com.ledger.domain.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.ledger.domain.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.ledger.domain.service.**$$serializer { *; }
-keepclassmembers class com.ledger.domain.service.** {
    *** Companion;
}
-keepclasseswithmembers class com.ledger.domain.service.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---------------------------------------------------------------- Koin
# Koin 的 `get<LedgerDatabase>()` 走 KClass + 反射构造，泛型实参信息必须保留。
-keepnames class kotlin.jvm.internal.DefaultConstructorMarker
-keep class org.koin.** { *; }
-keepattributes Signature, RuntimeVisibleParameterAnnotations, RuntimeInvisibleParameterAnnotations
-keepclassmembers class * {
    @org.koin.core.annotation.* <methods>;
}

# ---------------------------------------------------------------- SQLDelight / SQLCipher
# SQLDelight 的生成类通过 `Schema.create()` 反射式建表；SQLCipher 的 JNI 入口
# 全部在 `net.zetetic.database.sqlcipher` 包内，native 方法必须原样保留。
-keep class app.cash.sqldelight.** { *; }
-keep class net.zetetic.database.sqlcipher.** { *; }
-keep class net.zetetic.database.** { *; }
-keepclasseswithmembernames class net.zetetic.database.** {
    native <methods>;
}
-keepclasseswithmembernames class * {
    native <methods>;
}

# SQLite JDBC/驱动里有一些可选依赖（如 conscrypt），缺失属正常
-dontwarn net.zetetic.**
-dontwarn org.sqlite.**

# ---------------------------------------------------------------- Kotlin 协程
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ---------------------------------------------------------------- 其它
-dontwarn java.lang.instrument.**
-dontwarn org.slf4j.**
# Compose 由 AGP 自带规则覆盖，无需额外声明
