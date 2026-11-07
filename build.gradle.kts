import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.21"
    application
    `maven-publish`
}

group = "dev.aetherx86.rhizome"
version = "0.1.0"

repositories { mavenCentral() }

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    withSourcesJar()
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

application {
    mainClass.set("dev.aetherx86.rhizome.MainKt")
    applicationName = "rhizome"
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "rhizome"
            from(components["java"])
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/Aetherx86/Rhizome")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
