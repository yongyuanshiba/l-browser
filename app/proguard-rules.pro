# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   https://developer.android.com/topic/performance/app-optimization/enable-app-optimization

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Stage-4 probe P: SnakeYAML ships inside GeckoView and is only exercised when a
# GeckoView config file is read (GeckoRuntimeSettings configFilePath). Its
# TypeDescription class computes a logger name from
# TypeDescription.class.getPackage().getName() in a static initializer; R8 had
# renamed the class into the default package, where getPackage() returns null,
# so the first config-file read died with an NPE before the YAML was parsed.
# Keep the SnakeYAML classes under their real names (and packages).
-keep class org.yaml.snakeyaml.** { *; }
