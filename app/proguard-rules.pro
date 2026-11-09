# Keep the app's own classes as written (kotlinx.serialization models, data classes);
# libraries are still shrunk and optimised.
-keep class app.sideline.tv.** { *; }
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keepclassmembers class **$$serializer { *; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
