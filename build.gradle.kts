// Fichier de build racine : déclare les plugins sans les appliquer.
plugins {
    // AGP 9 embarque son propre support Kotlin pour les modules Android.
    alias(libs.plugins.android.application) apply false
    // Le module :core est un module JVM pur (sans Android) : il utilise le
    // plugin Kotlin/JVM classique, à la même version que le compilateur
    // intégré à AGP.
    alias(libs.plugins.kotlin.jvm) apply false
}
