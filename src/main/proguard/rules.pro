# Landmark — ProGuard rules
#
# Triggered by proguard-maven-plugin during the package phase.
# Input  : target/landmark-desktop-1.0.0-SNAPSHOT.jar (only com/landmarksoftware/**)
# Output : target/landmark-desktop-1.0.0-SNAPSHOT-obf.jar
# Mapping: target/proguard_map.txt   <-- keep this for stack-trace decoding

# ── Global behaviour ───────────────────────────────────────────────────
# Skip optimization — it routinely breaks Spring AOP and reflective lookups.
-dontoptimize

# Skip shrinking — many Spring beans/methods are reached only via reflection
# and would be removed as "unused" otherwise.
-dontshrink

# Skip preverification — JRE 1.8 rt.jar (used as the JDK class library
# reference because ProGuard 7.7.0 can't read JDK 25 jmods) is missing
# JDK 14+ classes like java.lang.Record. The preverifier can't resolve
# the class hierarchy without them. Output stays valid for JDK 7+.
-dontpreverify

# JRE 1.8 rt.jar lacks classes/methods added in JDK 9+. Treat all unresolved
# references as non-fatal — our code WILL run on the actual JDK 25 runtime.
-ignorewarnings
-dontskipnonpubliclibraryclasses

# Targeted suppressions for the JDK 9+/11+/14+/16+ APIs the app uses.
-dontwarn java.lang.Record
-dontwarn java.lang.invoke.StringConcatFactory
-dontwarn java.lang.runtime.ObjectMethods
-dontwarn java.lang.String
-dontwarn java.util.List
-dontwarn java.util.Map
-dontwarn java.util.Set
-dontwarn java.util.Optional
-dontwarn java.util.stream.Stream
-dontwarn com.landmarksoftware.**

# Suppress notes/warnings from library classes ProGuard doesn't process.
-dontnote
-dontwarn

# Allow tightening of access modifiers on members ProGuard does rename.
-allowaccessmodification

# ── Debug-info stripping ───────────────────────────────────────────────
# Drop source filename, line numbers, and local-variable tables from
# bytecode. Stack traces will show only obfuscated names — preserve
# proguard_map.txt to decode them later.
-keepattributes !SourceFile,!LineNumberTable,!LocalVariableTable,!LocalVariableTypeTable

# Retain runtime-visible attributes Spring/JavaFX rely on.
-keepattributes *Annotation*,Signature,Exceptions,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleParameterAnnotations,RuntimeInvisibleParameterAnnotations

# ══════════════════════════════════════════════════════════════════════
# Application entry points
# ══════════════════════════════════════════════════════════════════════

# JavaFX Application launcher.
-keep public class com.landmarksoftware.desktop.FixedAssetsApplication {
    public static void main(java.lang.String[]);
    public *;
}

# Spring Boot @SpringBootApplication-annotated config.
-keep class com.landmarksoftware.desktop.SpringConfig { *; }

# ══════════════════════════════════════════════════════════════════════
# Spring stereotypes — keep CLASS NAMES (component scanning by name)
# Method/field names inside may be renamed: Spring resolves beans by
# class type, not by member identifier.
# ══════════════════════════════════════════════════════════════════════

-keep @org.springframework.stereotype.Component  class com.landmarksoftware.**
-keep @org.springframework.stereotype.Service    class com.landmarksoftware.**
-keep @org.springframework.stereotype.Repository class com.landmarksoftware.**
-keep @org.springframework.context.annotation.Configuration class com.landmarksoftware.**

# Spring DI is by constructor injection — constructors must remain callable.
-keepclassmembers class com.landmarksoftware.** {
    public <init>(...);
}

# @Bean method names ARE the bean names in Spring — never rename them.
-keepclassmembers @org.springframework.context.annotation.Configuration class com.landmarksoftware.** {
    @org.springframework.context.annotation.Bean *;
}

# @Transactional methods — keep so AOP/CGLIB proxies and runtime
# annotation lookup can find and override them.
-keepclassmembers class com.landmarksoftware.** {
    @org.springframework.transaction.annotation.Transactional *;
}

# Defensive: rare in this codebase but kept in case future code adds them.
-keepclassmembers class com.landmarksoftware.** {
    @org.springframework.beans.factory.annotation.Autowired *;
    @org.springframework.beans.factory.annotation.Value *;
    @jakarta.annotation.PostConstruct *;
    @jakarta.annotation.PreDestroy *;
}

# ══════════════════════════════════════════════════════════════════════
# JavaFX controllers
# ══════════════════════════════════════════════════════════════════════

# This codebase builds UI programmatically (no .fxml files at present),
# but FXMLLoader-style controllers are kept defensively in case .fxml
# files are added later. Both initialize() variants are matched.
-keepclassmembers class com.landmarksoftware.**.ui.** {
    public void initialize();
    public void initialize(java.net.URL, java.util.ResourceBundle);
}

# Keep @FXML-annotated members (fields and event handlers) for FXMLLoader.
-keepclassmembers class com.landmarksoftware.**.ui.** {
    @javafx.fxml.FXML *;
}

# ══════════════════════════════════════════════════════════════════════
# Model classes — JdbcTemplate row mapper targets
# Keep classes AND members so reflection-based mappers (and any future
# BeanPropertyRowMapper usage) can see field names verbatim.
# ══════════════════════════════════════════════════════════════════════

-keep class com.landmarksoftware.model.**         { *; }
-keep class com.landmarksoftware.payroll.model.** { *; }

# ══════════════════════════════════════════════════════════════════════
# Public enum values
# ══════════════════════════════════════════════════════════════════════

-keepclassmembers enum com.landmarksoftware.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    public static final ** *;
}

# ══════════════════════════════════════════════════════════════════════
# Exception classes — keep names so stack traces identify the type
# ══════════════════════════════════════════════════════════════════════

-keep class com.landmarksoftware.** extends java.lang.Throwable
