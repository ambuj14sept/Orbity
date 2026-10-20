# Keep class/method names so crash reports shared from the devices stay readable.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# Enum names (Flash, Filter) travel between watch and phone in Data Layer messages.
-keep enum com.orbity.shared.** { *; }
