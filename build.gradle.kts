import com.xuncorp.spw.workshop.gradle.PluginPermission

plugins {
    `java-library`
    id("com.xuncorp.spw.workshop") version "0.1.0-dev23"
}

group = "io.github.saltlyrics"
version = "0.1.9"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

dependencies {
    compileOnly("com.github.Moriafly:spw-workshop-api:0.1.0-dev23")
    annotationProcessor("org.pf4j:pf4j:3.12.0")
    implementation("net.jthink:jaudiotagger:3.0.1")
    testImplementation("com.github.Moriafly:spw-workshop-api:0.1.0-dev23")
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("file.encoding", "UTF-8")
}

tasks.processResources {
    from("third-party") { into("META-INF/third-party") }
    from("LICENSE") { into("META-INF") }
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

spmod {
    PluginClass = "io.github.saltlyrics.plugin.BilingualLyricsPlugin"
    PluginId = "io.github.saltlyrics.bilingual"
    PluginVersion = project.version.toString()
    PluginName = "spw 双语歌词顺序"
    PluginDescription = "调整双语歌词原文译文位置"
    PluginProvider = "asukua"
    PluginOpenSourceUrl = "https://github.com/asukua/spw-bilingual-lyrics"
    PluginHasConfig = true
    PluginPermissions = listOf(PluginPermission.CLASS_TRANSFORM)
}
