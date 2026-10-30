plugins {
    java
    application
    id("com.gradleup.shadow")
}

group = "com.zenith"
version = "1.0.0"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

repositories {
    maven("https://maven.2b2t.vc/releases") {
        content { includeGroupByRegex("com.github.rfresh2.*") }
    }
    maven("https://maven.2b2t.vc/remote")
    mavenLocal()
}

dependencies {
    implementation("io.javalin:javalin:6.7.0")
    implementation("org.slf4j:slf4j-simple:2.0.17")
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("org.jsoup:jsoup:1.21.2")
    implementation("net.raphimc:MinecraftAuth:5.0.2")
    // the in-game lobby speaks the same protocol version as the instances
    implementation("com.github.rfresh2:MCProtocolLib:1.21.4.73") {
        exclude(group = "io.netty")
    }
    // lets clients from 1.20.5 (when transfers were added) up to the newest version into the lobby
    implementation("com.viaversion:viaversion-common:5.12.1")
    implementation("com.viaversion:viabackwards-common:5.12.1")
    implementation(platform("io.netty:netty-bom:4.2.19.Final"))
    implementation("io.netty:netty-handler")
    implementation("io.netty:netty-codec-haproxy")
    implementation("io.netty:netty-resolver-dns")
    implementation("io.netty:netty-transport-classes-epoll")
    // the optional central Discord bot
    implementation("com.github.rfresh2:JDA:6.7.37") {
        exclude(group = "club.minnced")
        exclude(group = "net.java.dev.jna")
        exclude(group = "com.google.crypto.tink")
    }

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "com.zenith.manager.ManagerMain"
}

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release = 25
    }
    test {
        useJUnitPlatform()
    }
    shadowJar {
        archiveBaseName = "zenith-manager"
        archiveClassifier = ""
        archiveVersion = ""
        mergeServiceFiles()
    }
}
