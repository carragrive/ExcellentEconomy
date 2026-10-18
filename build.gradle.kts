plugins {
    id("java-library")
    id("maven-publish")
}

group = "su.nightexpress.excellenteconomy"
version = "3.0.2"

java {
    toolchain {
         languageVersion = JavaLanguageVersion.of(25)
    }
    withSourcesJar()
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.nightexpressdev.com/releases")
    maven("https://jitpack.io")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    maven("https://repo.rosewooddev.io/repository/public/")
    maven("https://repo.codemc.io/repository/creatorfromhell/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.+")
    compileOnly("net.milkbowl.vault:VaultUnlockedAPI:2.20") {
        exclude(group = "org.bukkit", module = "bukkit")
    }
    compileOnly("su.nightexpress.nightcore:main:2.16.7")
    compileOnly("me.clip:placeholderapi:2.11.6")
    compileOnly("org.black_ixx:playerpoints:3.0.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testImplementation("org.mockito:mockito-core:5.24.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.h2database:h2:2.3.232")
    testImplementation("org.xerial:sqlite-jdbc:3.49.1.0")
    testRuntimeOnly("com.mysql:mysql-connector-j:9.2.0")
}

configurations.testImplementation {
    extendsFrom(configurations.compileOnly.get())
}

tasks {
    test {
        useJUnitPlatform()
    }
    withType<JavaCompile> {
        options.encoding = "UTF-8"
    }

    processResources {
        val pluginVersion = project.version.toString()
        inputs.property("pluginVersion", pluginVersion)
        // Replicates maven <filtering>true</filtering> for plugin.yml
        // Replaces ${version} with the project version.
        filesMatching("*plugin.yml") {
            expand(mapOf("version" to pluginVersion))
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifact(tasks.named("jar"))
            artifact(tasks.named("sourcesJar")) {
                classifier = "sources"
            }
            artifactId = project.name
        }
    }
    repositories {
        maven {
                name = "nightexpress"
                url = uri("https://repo.nightexpressdev.com/releases")
                credentials {
                    username = System.getenv("REPOSILITE_USER")
                    password = System.getenv("REPOSILITE_PASSWORD")
                }
        }
    }
}
