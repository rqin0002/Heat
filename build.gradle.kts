// Project-level build plugins and their shared versions.
buildscript {
    dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21") }
}
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
    id("com.google.devtools.ksp") version "2.3.6" apply false
    id("com.google.gms.google-services") version "4.4.4" apply false
}
