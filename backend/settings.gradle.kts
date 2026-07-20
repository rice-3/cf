// Corretto 25 を Gradle Toolchain で自動取得するための resolver（要件 C-01 / D-A01）
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "cf-training-backend"
