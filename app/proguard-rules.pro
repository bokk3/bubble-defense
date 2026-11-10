# ProGuard rules for Bubble Defense

# Preserve packed value classes and their inline members
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

-keep,allowobfuscation @kotlin.jvm.JvmInline class * {
    *;
}

-keepclassmembers class com.arcade.bubbledefense.engine.math.Vec2 {
    public long getPacked();
    public static *** getPacked-impl(long);
}

# Preserve entity pools and SoA arrays from member stripping
-keep class com.arcade.bubbledefense.engine.pool.** {
    *;
}

-keep class com.arcade.bubbledefense.domain.world.** {
    *;
}
