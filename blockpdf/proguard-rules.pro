# R8-Schutzregeln für BlockPDF (siehe app/proguard-rules.pro für den
# Grundsatz): PDFBox löst Schrift-, Codec- und Filterklassen teils per
# Reflexion auf — die bleiben unangetastet.
-keep class com.tom_roush.** { *; }
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
