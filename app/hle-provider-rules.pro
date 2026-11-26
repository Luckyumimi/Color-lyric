# Upstream Pack DEX links to the original host ABI by name.
-keep class com.juren233.hyperlyricsenhanced.provider.** { *; }
-keep class shop.terramoor.github.io.lyrics.provider.HleProviderModule { *; }

# Runtime classes referenced only from upstream Pack DEX.
-keep class kotlin.** { *; }
-keep class io.github.proify.lyricon.provider.** { *; }
-keep class io.github.proify.lyricon.lyric.model.** { *; }
-keep class kotlinx.coroutines.BuildersKt { *; }
-keep class kotlinx.coroutines.CompletableJob { *; }
-keep class kotlinx.coroutines.CoroutineDispatcher { *; }
-keep class kotlinx.coroutines.CoroutineScope { *; }
-keep class kotlinx.coroutines.CoroutineScopeKt { *; }
-keep class kotlinx.coroutines.CoroutineStart { *; }
-keep class kotlinx.coroutines.DelayKt { *; }
-keep class kotlinx.coroutines.Dispatchers { *; }
-keep class kotlinx.coroutines.Job { *; }
-keep class kotlinx.coroutines.Job$DefaultImpls { *; }
-keep class kotlinx.coroutines.SupervisorKt { *; }
-keep class kotlinx.serialization.** { *; }
-keep class org.luckypray.dexkit.** { *; }
