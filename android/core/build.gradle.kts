import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

abstract class SplitTranslations : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val whole: RegularFileProperty

    @get:OutputDirectory
    abstract val split: DirectoryProperty

    @TaskAction
    fun write() {
        val folder = split.get().dir("translations").asFile
        folder.deleteRecursively()
        folder.mkdirs()
        @Suppress("UNCHECKED_CAST")
        val tables = groovy.json.JsonSlurper().parse(whole.get().asFile, "UTF-8") as Map<String, Map<String, String>>
        val json = groovy.json.JsonGenerator.Options().disableUnicodeEscaping().build()
        for ((language, table) in tables) File(folder, "$language.json").writeText(json.toJson(table), Charsets.UTF_8)
    }
}

val splitTranslations = tasks.register<SplitTranslations>("splitTranslations") {
    whole.set(layout.projectDirectory.file("../../Sources/OzenKit/Resources/Translations.json"))
    split.set(layout.buildDirectory.dir("generated/translations"))
}

sourceSets {
    main {
        resources.srcDir("../../Sources/OzenKit/Resources")
        resources.srcDir(splitTranslations)
        resources.exclude("Translations.json")
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("ozen.fixtures", rootProject.file("../Tests/Fixtures").absolutePath)
    inputs.dir("../../Tests/Fixtures")
    inputs.file("../../Sources/OzenKit/Resources/Translations.json")
    inputs.files(
        "../../README.md",
        fileTree("../../docs") { include("*.md") },
        fileTree("../../server") { include("README.md", "*.py", "*.ps1", "*.cmd") },
    )
}
