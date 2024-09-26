import groovy.util.Node
import groovy.xml.XmlParser
import org.gradle.api.JavaVersion.VERSION_17
import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.*
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.extensions.IntelliJPlatformDependenciesExtension
import org.jetbrains.intellij.platform.gradle.extensions.IntelliJPlatformTestingExtension
import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask
import org.jetbrains.intellij.platform.gradle.utils.extensionProvider
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.*

// The same as `--stacktrace` param
gradle.startParameter.showStacktrace = ShowStacktrace.ALWAYS

@Suppress("DSL_SCOPE_VIOLATION")
plugins {
    id("java") // Java support
    alias(libs.plugins.changelog) // Gradle Changelog Plugin
    alias(libs.plugins.qodana) // Gradle Qodana Plugin
    alias(libs.plugins.kover) // Gradle Kover Plugin
    alias(libs.plugins.serialization)
    alias(libs.plugins.gradleIntelliJPlugin)

    id("org.jetbrains.grammarkit") version "2022.3.2.2"

    kotlin("jvm") version "1.8.22"
    id("net.saliman.properties") version "1.5.2"
}

fun properties(key: String) = providers.gradleProperty(key)
fun environment(key: String) = providers.environmentVariable(key)

fun prop(name: String): String =
    extra.properties[name] as? String
        ?: error("Property `$name` is not defined in gradle.properties")

val basePluginArchiveName = "autodev-jetbrains"

val javaScriptPlugins = listOf("JavaScript")
val pycharmPlugins = listOf("PythonCore")
val javaPlugins = listOf("com.intellij.java", "org.jetbrains.kotlin")
val clionVersion = prop("clionVersion")

// https://plugins.jetbrains.com/docs/intellij/plugin-compatibility.html#modules-specific-to-functionality
val clionPlugins = listOf(
    "com.intellij.cidr.base",
    "com.intellij.cidr.lang",
    "com.intellij.clion",
    prop("rustPlugin"),
    "org.toml.lang"
)
var cppPlugins: List<String> = listOf(
    "com.intellij.cidr.lang",
    "com.intellij.clion",
    "com.intellij.cidr.base",
    "org.jetbrains.plugins.clion.test.google",
    "org.jetbrains.plugins.clion.test.catch"
)

val rustPlugins = listOf(
    prop("rustPlugin"),
    "org.toml.lang"
)

val riderVersion = prop("riderVersion")
val riderPlugins: List<String> = listOf(
    "rider-plugins-appender",
    "org.intellij.intelliLang",
)
val scalaPlugin = prop("scalaPlugin")

val pluginProjects: List<Project> get() = rootProject.allprojects.toList()
val ideaPlugins =
    listOf(
        "com.intellij.java",
        "org.jetbrains.plugins.gradle",
        "org.jetbrains.idea.maven",
        "org.jetbrains.kotlin",
        "JavaScript"
    )

var baseIDE = prop("baseIDE")
val platformVersion = prop("platformVersion").toInt()
val ideaVersion = prop("ideaVersion")
val golandVersion = prop("golandVersion")
val pycharmVersion = prop("pycharmVersion")
val webstormVersion = prop("webstormVersion")

var lang = extra.properties["lang"] ?: "java"

val baseVersion = when (baseIDE) {
    "idea" -> ideaVersion
    "pycharm" -> pycharmVersion
    "goland" -> golandVersion
    "clion" -> clionVersion
    "rider" -> riderVersion
    "javascript" -> webstormVersion
    else -> error("Unexpected IDE name: `$baseIDE`")
}

repositories {
    mavenCentral()
}

allprojects {
    apply {
        plugin("idea")
        plugin("kotlin")
        plugin("org.jetbrains.kotlinx.kover")
    }

    repositories {
        mavenCentral()
    }

    idea {
        module {
            generatedSourceDirs.add(file("src/gen"))
            isDownloadJavadoc = true
            isDownloadSources = true
        }
    }

    configure<JavaPluginExtension> {
        sourceCompatibility = VERSION_17
        targetCompatibility = VERSION_17
    }

    tasks {
        withType<KotlinCompile> {
            kotlinOptions {
                jvmTarget = VERSION_17.toString()
                languageVersion = "1.8"
                // see https://plugins.jetbrains.com/docs/intellij/using-kotlin.html#kotlin-standard-library
                apiVersion = "1.7"
                freeCompilerArgs = listOf("-Xjvm-default=all")
            }
        }

        // All these tasks don't make sense for non-root subprojects
        // Root project (i.e. `:plugin`) enables them itself if needed
        runIde { enabled = false }
        prepareSandbox { enabled = false }
        buildSearchableOptions { enabled = false }
    }

    val testOutput = configurations.create("testOutput")

    if(this.name != "ext-terminal") {
        sourceSets {
            main {
                java.srcDirs("src/gen")
                if (platformVersion == 241) {
                    resources.srcDirs("src/233/main/resources")
                }
                resources.srcDirs("src/$platformVersion/main/resources")
            }
            test {
                resources.srcDirs("src/$platformVersion/test/resources")
            }
        }
        kotlin {
            sourceSets {
                main {
                    // share 233 code to 241
                    if (platformVersion == 241) {
                        kotlin.srcDirs("src/233/main/kotlin")
                    }
                    kotlin.srcDirs("src/$platformVersion/main/kotlin")
                }
                test {
                    kotlin.srcDirs("src/$platformVersion/test/kotlin")
                }
            }
        }
    }

    dependencies {
        compileOnly(kotlin("stdlib-jdk8"))
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

        testOutput(sourceSets.getByName("test").output.classesDirs)
    }
}

changelog {
    version.set(properties("pluginVersion"))
    groups.empty()
    path.set(rootProject.file("CHANGELOG.md").toString())
    repositoryUrl.set(properties("pluginRepositoryUrl"))
}

project(":plugin") {
    apply {
        plugin("org.jetbrains.changelog")
    }

    version = prop("pluginVersion") + "-$platformVersion"

    intellij {
        pluginName.set(basePluginArchiveName)
        val pluginList: MutableList<String> = mutableListOf("Git4Idea")
        when (lang) {
            "idea" -> {
                pluginList += javaPlugins
            }

            "scala" -> {
                pluginList += javaPlugins + scalaPlugin
            }

            "python" -> {
                pluginList += pycharmPlugins
            }

            "go" -> {
                pluginList += listOf("org.jetbrains.plugins.go")
            }

            "cpp" -> {
                pluginList += clionPlugins
            }

            "rust" -> {
                pluginList += rustPlugins
            }
        }

        plugins.set(pluginList)
    }

    dependencies {
        implementation(project(":"))
        implementation(project(":java"))
        implementation(project(":kotlin"))
        implementation(project(":pycharm"))
        implementation(project(":javascript"))
        implementation(project(":goland"))
        implementation(project(":rust"))
        implementation(project(":cpp"))
        implementation(project(":scala"))

        implementation(project(":local-bundle"))

        implementation(project(":exts:ext-database"))
        implementation(project(":exts:ext-android"))
        implementation(project(":exts:ext-harmonyos"))
        implementation(project(":exts:ext-git"))
        implementation(project(":exts:ext-http-client"))
        implementation(project(":exts:ext-terminal"))
        implementation(project(":exts:devins-lang"))
    }
}

project(":") {
    intellij {
        version.set(ideaVersion)
        plugins.set(ideaPlugins)
    }

    dependencies {
        implementation(libs.bundles.openai)
        implementation(libs.bundles.markdown)
        implementation(libs.yaml)

        implementation(libs.json.pathkt)

        implementation("org.jetbrains:markdown:0.6.1")
        implementation(libs.kotlinx.serialization.json)

        // chocolate factory
        // follow: https://onnxruntime.ai/docs/get-started/with-java.html
//        implementation("com.microsoft.onnxruntime:onnxruntime:1.18.0")
//        implementation("ai.djl.huggingface:tokenizers:0.29.0")

        implementation("cc.unitmesh:cocoa-core:1.0.0")
        implementation("cc.unitmesh:document:1.0.0")

        // kanban
        implementation(libs.github.api)
        implementation("org.gitlab4j:gitlab4j-api:5.3.0")

        // template engine
        implementation("org.apache.velocity:velocity-engine-core:2.3")

        // http request/response
        implementation(libs.jackson.module.kotlin)

        // token count
        implementation("com.knuddels:jtokkit:1.0.0")

        implementation("org.apache.commons:commons-text:1.12.0")

        // junit
        testImplementation("io.kotest:kotest-assertions-core:5.7.2")
        testImplementation("junit:junit:4.13.2")
        testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.9.3")

        kover(project(":"))
        kover(project(":cpp"))
        kover(project(":csharp"))
        kover(project(":goland"))
        kover(project(":java"))
        kover(project(":javascript"))
        kover(project(":kotlin"))
        kover(project(":pycharm"))
        kover(project(":rust"))
        kover(project(":scala"))

        kover(project(":exts:ext-database"))
        kover(project(":exts:ext-android"))
        kover(project(":exts:devins-lang"))
    }

    task("resolveDependencies") {
        doLast {
            rootProject.allprojects
                .map { it.configurations }
                .flatMap { it.filter { c -> c.isCanBeResolved } }
                .forEach { it.resolve() }
        }
    }

    tasks {
        buildPlugin {
            dependsOn(createSourceJar)
            from(createSourceJar) { into("lib/src") }
            // Set proper name for final plugin zip.
            // Otherwise, base name is the same as gradle module name
            archiveBaseName.set(basePluginArchiveName)
        }

        runIde { enabled = true }

        prepareSandbox {
            finalizedBy(mergePluginJarTask)
            enabled = true
        }

        buildSearchableOptions {
            // Force `mergePluginJarTask` be executed before `buildSearchableOptions`
            // Otherwise, `buildSearchableOptions` task can't load the plugin and searchable options are not built.
            // Should be dropped when jar merging is implemented in `gradle-intellij-plugin` itself
            dependsOn(mergePluginJarTask)
            enabled = false
        }

        withType<RunIdeTask> {
            // Default args for IDEA installation
            jvmArgs("-Xmx768m", "-XX:+UseG1GC", "-XX:SoftRefLRUPolicyMSPerMB=50")
            // Disable plugin auto reloading. See `com.intellij.ide.plugins.DynamicPluginVfsListener`
            jvmArgs("-Didea.auto.reload.plugins=false")
            // Don't show "Tip of the Day" at startup
            jvmArgs("-Dide.show.tips.on.startup.default.value=false")
            // uncomment if `unexpected exception ProcessCanceledException` prevents you from debugging a running IDE
            // jvmArgs("-Didea.ProcessCanceledException=disabled")
        }

        withType<PatchPluginXmlTask> {
            pluginDescription.set(provider { file("description.html").readText() })

            changelog {
                version.set(properties("pluginVersion"))
                groups.empty()
                path.set(rootProject.file("CHANGELOG.md").toString())
                repositoryUrl.set(properties("pluginRepositoryUrl"))
            }

            val changelog = project.changelog
            // Get the latest available change notes from the changelog file
            changeNotes.set(properties("pluginVersion").map { pluginVersion ->
                with(changelog) {
                    renderItem(
                        (getOrNull(pluginVersion) ?: getUnreleased())
                            .withHeader(false)
                            .withEmptySections(false),

                        Changelog.OutputType.HTML,
                    )
                }
            })
        }

        withType<PublishPluginTask> {
            dependsOn("patchChangelog")
            token.set(environment("PUBLISH_TOKEN"))
            channels.set(properties("pluginVersion").map {
                listOf(it.split('-').getOrElse(1) { "default" }.split('.').first())
            })
        }
    }
}

project(":pycharm") {
    intellij {
        version.set(pycharmVersion)
        plugins.set(pycharmPlugins)
    }
    dependencies {
        implementation(project(":"))
    }
}


project(":java") {
    intellij {
        version.set(ideaVersion)
        plugins.set(ideaPlugins)
    }
    dependencies {
        implementation(project(":"))
    }
}

project(":javascript") {
    intellij {
        version.set(ideaVersion)
        plugins.set(javaScriptPlugins)
    }
    dependencies {
        implementation(project(":"))
    }
}

project(":kotlin") {
    intellij {
        version.set(ideaVersion)
        plugins.set(ideaPlugins)
    }
    dependencies {
        implementation(project(":"))
        implementation(project(":java"))
    }
}

project(":scala") {
    intellij {
        version.set(ideaVersion)
        plugins.set(ideaPlugins + scalaPlugin)
    }
    dependencies {
        implementation(project(":"))
        implementation(project(":java"))
    }
}

project(":rust") {
    intellij {
        version.set(ideaVersion)
        plugins.set(rustPlugins)

        sameSinceUntilBuild.set(true)
        updateSinceUntilBuild.set(false)
    }
    dependencies {
        implementation(project(":"))
    }
}

project(":cpp") {
    if (platformVersion == 233 || platformVersion == 241) {
        cppPlugins += "com.intellij.nativeDebug"
    }

    intellij {
        version.set(clionVersion)
        plugins.set(cppPlugins)
    }
    dependencies {
        implementation(project(":"))
    }
}

project(":csharp") {
    intellij {
        version.set(riderVersion)
        type.set("RD")
        plugins.set(riderPlugins)
    }
    dependencies {
        implementation(project(":"))
    }
}

project(":goland") {
    intellij {
        version.set(ideaVersion)
        updateSinceUntilBuild.set(false)
        // required if Go language API is needed:
        plugins.set(prop("goPlugin").split(',').map(String::trim).filter(String::isNotEmpty))
    }
    dependencies {
        implementation(project(":"))
    }
}

project(":exts:ext-database") {
    intellij {
        version.set(ideaVersion)
        plugins.set(ideaPlugins + "com.intellij.database")
    }
    dependencies {
        implementation(project(":"))
    }
}

project(":exts:ext-android") {
    intellij {
        version.set(ideaVersion)
        type.set("AI") // means Android Studio
        plugins.set((ideaPlugins + prop("androidPlugin").ifBlank { "" }).filter(String::isNotEmpty))
    }

    dependencies {
        implementation(project(":"))
    }
}

project(":exts:ext-harmonyos") {
    intellij {
        version.set(ideaVersion)
        type.set("AI") // means Android Studio
        plugins.set((ideaPlugins + prop("androidPlugin").ifBlank { "" }).filter(String::isNotEmpty))
    }

    dependencies {
        implementation(project(":"))
    }
}

project(":exts:ext-git") {
    intellij {
        version.set(ideaVersion)
        plugins.set(ideaPlugins + "Git4Idea")
    }

    dependencies {
        implementation(project(":"))
        implementation("cc.unitmesh:git-commit-message:0.4.6")
    }
}

project(":exts:ext-http-client") {
    intellij {
        version.set(ideaVersion)
        plugins.set(ideaPlugins + "com.jetbrains.restClient")
    }

    dependencies {
        implementation(project(":"))
    }
}

project(":local-bundle") {
    intellij {
        version.set(ideaVersion)
    }

    dependencies {
        implementation(project(":"))
    }
}

project(":exts:ext-terminal") {
    intellij {
        version.set(ideaVersion)
        plugins.set((ideaPlugins + "org.jetbrains.plugins.terminal"))
    }

    dependencies {
        implementation(project(":"))
    }

    sourceSets {
        main {
            resources.srcDirs("src/$platformVersion/main/resources")
        }
        test {
            resources.srcDirs("src/$platformVersion/test/resources")
        }
    }
    kotlin {
        sourceSets {
            main {
                kotlin.srcDirs("src/$platformVersion/main/kotlin")
            }
            test {
                kotlin.srcDirs("src/$platformVersion/test/kotlin")
            }
        }
    }
}

project(":exts:devins-lang") {
    apply {
        plugin("org.jetbrains.grammarkit")
    }

    intellij {
        version.set(ideaVersion)
        plugins.set((ideaPlugins + "org.intellij.plugins.markdown" + "com.jetbrains.sh" + "Git4Idea"))
    }

    dependencies {
        implementation(project(":"))
        implementation(project(":exts:ext-git"))
    }

    tasks {
        generateLexer {
            sourceFile.set(file("src/grammar/DevInLexer.flex"))
            targetOutputDir.set(file("src/gen/cc/unitmesh/devti/language/lexer"))
            purgeOldFiles.set(true)
        }

        generateParser {
            sourceFile.set(file("src/grammar/DevInParser.bnf"))
            targetRootOutputDir.set(file("src/gen"))
            pathToParser.set("cc/unitmesh/devti/language/parser/DevInParser.java")
            pathToPsiRoot.set("cc/unitmesh/devti/language/psi")
            purgeOldFiles.set(true)
        }

        withType<KotlinCompile> {
            dependsOn(generateLexer, generateParser)
        }
    }
}

fun File.isPluginJar(): Boolean {
    if (!isFile) return false
    if (extension != "jar") return false
    return zipTree(this).files.any { it.isManifestFile() }
}

fun File.isManifestFile(): Boolean {
    if (extension != "xml") return false
    val rootNode = try {
        val parser = XmlParser()
        parser.parse(this)
    } catch (e: Exception) {
        logger.error("Failed to parse $path", e)
        return false
    }
    return rootNode.name() == "idea-plugin"
}
