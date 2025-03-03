plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.8.21"
    id("org.jetbrains.intellij") version "1.17.3" // Updated to latest version
}

group = "com.felbus.contexter"
version = "1.0.0"

repositories {
    mavenCentral()
}

// Configure IntelliJ Plugin
intellij {
    version.set("2024.1") // Use stable release version
    type.set("IU") // Change to "IC" if targeting Community Edition
    plugins.set(listOf("java")) // Add more plugins if needed
}

tasks {
    patchPluginXml {
        sinceBuild.set("241.0") // Ensure compatibility with 2024.1
        untilBuild.set("242.*") // Allow future IntelliJ versions to use the plugin
        changeNotes.set("""
            Initial release of Context Builder.
            - Auto-complete file selection
            - Add extra code snippets and prompts to context
            - Copy the final context to clipboard
        """.trimIndent())
    }

    // Ensure correct JVM compatibility for IntelliJ 2024+
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }

    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN") ?: "")
        privateKey.set(System.getenv("PRIVATE_KEY") ?: "")
        password.set(System.getenv("PRIVATE_KEY_PASSWORD") ?: "")
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN") ?: "")
    }
}

