plugins {
    id("net.fabricmc.fabric-loom") version "1.19.3"
}

version = property("mod_version") as String
group = property("maven_group") as String

base {
    archivesName.set(property("archives_base_name") as String)
}

repositories {
    mavenCentral()
    maven("https://repo.polyfrost.org/releases")
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")

    // Polyfrost OneClient Server-API, bundled inside our jar
    implementation(include("org.polyfrost.oneclient:api:${property("oneclient_api_version")}")!!)
    implementation(include("org.polyfrost.oneclient:common:${property("oneclient_api_version")}")!!)
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.encoding = "UTF-8"
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}
