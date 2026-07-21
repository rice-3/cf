import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    java
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.spring") version "2.4.10"
    kotlin("plugin.jpa") version "2.4.10"
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.diffplug.spotless") version "7.0.2"
}

// コード整形（format品質ゲート、詳細設計 §13.4）。
// spotlessCheck は check → build に自動で組み込まれるため、CIの `gradlew build` で検証される。
// Kotlin主体のためktlintを適用。Java向けの javac 内部API依存フォーマッタは JDK 25 で不安定なため
// 現時点ではKotlinのみを対象とする（Java整形は残タスク）。
spotless {
    val ktlintOverrides = mapOf(
        "ktlint_code_style" to "intellij_idea",
        // 日本語コメント行が長くなるため行長ルールは無効化。
        "ktlint_standard_max-line-length" to "disabled",
        // ヘキサゴナルの adapter.in.* は `in`（Kotlin予約語）をバッククォート必須とするため誤検知する。
        "ktlint_standard_package-name" to "disabled",
        // 関連宣言を1ファイルにまとめる方針のため filename ルールは無効化。
        "ktlint_standard_filename" to "disabled",
    )
    kotlin {
        target("src/**/*.kt")
        ktlint("1.5.0").editorConfigOverride(ktlintOverrides)
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint("1.5.0").editorConfigOverride(ktlintOverrides)
    }
}

group = "com.example.cf"
version = "0.1.0-SNAPSHOT"

// JVMはAmazon Corretto 25へ統一（要件 C-01、技術選定書 §15.1）
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
        vendor.set(JvmVendorSpec.AMAZON)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // --- Spring Boot ---
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // --- Kotlin ---
    // Boot 4はJackson 3（tools.jackson）が既定
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // --- DB ---
    // Boot 4はオートコンフィグがモジュール分割されたためstarter-flywayを使用する
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // --- AWS SDK (S3 Adapter) ---
    implementation(platform("software.amazon.awssdk:bom:2.29.45"))
    implementation("software.amazon.awssdk:s3")
    implementation("software.amazon.awssdk:sesv2")

    // --- 分散ロック（バッチ多重起動防止, 基本設計 §8.3、ADR-0003） ---
    implementation("net.javacrumbs.shedlock:shedlock-spring:7.7.0")
    implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:7.7.0")

    // --- ID採番（ULID, 詳細設計 §3.3） ---
    implementation("com.github.f4b6a3:ulid-creator:5.2.3")

    // --- Test ---
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
    testImplementation("io.mockk:mockk:1.14.2")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.1")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.3"))
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
    }
}
