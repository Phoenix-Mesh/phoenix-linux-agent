// SPDX-License-Identifier: Apache-2.0

import java.time.Duration
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    application
    java
}

group = "com.phoenix"
version = "0.3.0"

repositories {
    mavenCentral()
}

dependencies {
    // The only runtime dependency, the same MQTT client Phoenix Core uses.
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("com.phoenix.linuxagent.Main")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-options", "-Werror"))
}

// The agent runs on Java 11 or newer, so old machines can take part. The
// build itself needs JDK 17 or newer (Gradle and JUnit 6 require it).
tasks.compileJava {
    options.release.set(11)
}

tasks.compileTestJava {
    options.release.set(17)
}

// One self-contained jar: java -jar phoenix-linux-agent-v<version>.jar
tasks.jar {
    archiveFileName.set("phoenix-linux-agent-v${project.version}.jar")
    manifest {
        attributes(
            "Main-Class" to application.mainClass.get(),
            "Implementation-Title" to "Phoenix Linux Node Agent",
            "Implementation-Version" to project.version
        )
    }
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }
    }) {
        exclude("META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA",
            "META-INF/*.EC", "module-info.class", "META-INF/versions/*/module-info.class")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.test {
    useJUnitPlatform()
    timeout.set(Duration.ofMinutes(10))
    testLogging {
        events("failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
    }
}
