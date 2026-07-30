# Dorina Agent Android ProGuard Rules
# Keep all Kotlin data classes used for JSON serialization
-keep class com.dorina.agent.** { *; }

# MediaPipe GenAI
-keep class com.google.mediapipe.** { *; }

# Timber
-dontwarn com.jakewharton.timber.**
