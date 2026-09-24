# Preserve native entry points used by NoMessages, libsignal and SQLCipher.
-keepclasseswithmembers,includedescriptorclasses class * {
    native <methods>;
}

-keep class org.signal.libsignal.** { *; }
-keep class net.zetetic.database.** { *; }

# Release artifacts must not retain application log calls.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}
