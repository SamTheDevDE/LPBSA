import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("kapt") version "2.4.10"
    id("com.gradleup.shadow") version "9.6.1"
    id("xyz.jpenilla.run-velocity") version "3.1.0"
}

group = providers.gradleProperty("group").get()
version = providers.gradleProperty("version").get()

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:3.5.0-SNAPSHOT")
    kapt("com.velocitypowered:velocity-api:3.5.0-SNAPSHOT")
    compileOnly("net.luckperms:api:5.5")

    implementation("org.spongepowered:configurate-yaml:4.2.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testImplementation("com.velocitypowered:velocity-api:3.5.0-SNAPSHOT")
    testImplementation("net.luckperms:api:5.5")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
}

kotlin {
    jvmToolchain(25)
    compilerOptions.jvmTarget.set(JvmTarget.JVM_25)
}

val generatedBuildInfo = layout.buildDirectory.dir("generated/sources/build-info/kotlin")

val buildVersion = project.version.toString()
val generateBuildInfo = tasks.register<Copy>("generateBuildInfo") {
    from("src/templates/build-info") {
        expand("version" to buildVersion)
        rename { it.removeSuffix(".template") }
    }
    into(generatedBuildInfo)
}

kotlin.sourceSets.main {
    kotlin.srcDir(generatedBuildInfo)
}

tasks {
    compileKotlin {
        dependsOn(generateBuildInfo)
    }

    matching { it.name == "kaptGenerateStubsKotlin" }.configureEach {
        dependsOn(generateBuildInfo)
    }

    test {
        useJUnitPlatform()
    }

    jar {
        enabled = false
    }

    shadowJar {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        archiveBaseName.set("LPBSA")
        archiveClassifier.set("")
        archiveVersion.set(project.version.toString())
        relocate("org.spongepowered.configurate", "de.samthedev.lpbsa.lib.configurate")
        relocate("org.yaml.snakeyaml", "de.samthedev.lpbsa.lib.snakeyaml")
        relocate("io.leangen.geantyref", "de.samthedev.lpbsa.lib.geantyref")
        relocate("net.kyori.option", "de.samthedev.lpbsa.lib.kyori.option")
        manifest {
            attributes(
                "Implementation-Title" to "LPBSA",
                "Implementation-Version" to project.version,
            )
        }
    }

    build {
        dependsOn(shadowJar)
    }

    runVelocity {
        velocityVersion("3.5.0-SNAPSHOT")
    }
}
