import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.10"
    id("com.gradleup.shadow") version "9.4.2"
    id("org.jetbrains.dokka") version "2.0.0"
    id("com.vanniktech.maven.publish") version "0.33.0"
}

group = "io.github.nitanmarcel"
version = "0.1.0-beta.5"

repositories {
    mavenCentral()
    google()
}

dependencies {
    val jadxVersion = "1.5.6"

    // provided by jadx at runtime, excluded from the plugin jar
    compileOnly("io.github.skylot:jadx-core:$jadxVersion")
    compileOnly("io.github.skylot:jadx-dex-input:$jadxVersion")
    compileOnly("io.github.skylot:jadx-java-input:$jadxVersion")
    compileOnly("org.slf4j:slf4j-api:2.0.17")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

tasks {
    processResources {
        val v = project.version.toString()
        inputs.property("version", v)
        filesMatching("jadx-emu.properties") {
            expand("version" to v)
        }
    }

    shadowJar {
        archiveClassifier = "plugin"
        mergeServiceFiles()
    }

    register<Copy>("dist") {
        group = "jadx-plugin"
        dependsOn(shadowJar)
        from(shadowJar) {
            rename { "jadx-emu-${project.version}.jar" }
        }
        into(layout.buildDirectory.dir("dist"))
    }
}

afterEvaluate {
    components.named<AdhocComponentWithVariants>("java") {
        withVariantsFromConfiguration(configurations["shadowRuntimeElements"]) { skip() }
    }
}

mavenPublishing {
    configure(KotlinJvm(javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"), sourcesJar = true))
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = false)
    if (providers.gradleProperty("signingInMemoryKey").isPresent || providers.gradleProperty("signing.keyId").isPresent) {
        signAllPublications()
    }

    coordinates(group.toString(), "jadx-emu", version.toString())

    pom {
        name = "jadx-emu"
        description = "Extensible jadx plugin for dalvik emulation.x"
        url = "https://github.com/nitanmarcel/jadx-emu"
        inceptionYear = "2026"
        licenses {
            license {
                name = "GNU General Public License v2.0"
                url = "https://www.gnu.org/licenses/old-licenses/gpl-2.0.txt"
            }
        }
        developers {
            developer {
                id = "nitanmarcel"
                name = "Marcel Alexandru Nitan"
                url = "https://github.com/nitanmarcel"
            }
        }
        scm {
            url = "https://github.com/nitanmarcel/jadx-emu"
            connection = "scm:git:https://github.com/nitanmarcel/jadx-emu.git"
            developerConnection = "scm:git:ssh://git@github.com/nitanmarcel/jadx-emu.git"
        }
    }
}
