plugins {
    java
}

group = "itemcounters"
version = "1.0.0"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

dependencies {
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.6") { isTransitive = false }
    compileOnly("org.purpurmc.purpur:purpur-api:1.21.10-R0.1-SNAPSHOT")
    compileOnly("me.clip:placeholderapi:2.11.6")
    testImplementation("org.purpurmc.purpur:purpur-api:1.21.10-R0.1-SNAPSHOT")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }
tasks.processResources { filesMatching("plugin.yml") { expand("version" to project.version) } }
tasks.jar {
    from("LICENSE") { into("META-INF/item-counters") }
    from("third-party/licenses") { into("META-INF/item-counters/third-party") }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "module-info.class", "META-INF/versions/**/module-info.class")
    // Retain SQLite JNI for the supported 64-bit architectures; avoid shipping unused native targets.
    exclude {
        !it.isDirectory && it.path.startsWith("org/sqlite/native/") &&
            !it.path.contains("/x86_64/") && !it.path.contains("/aarch64/")
    }
    archiveBaseName.set("item-counters")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
