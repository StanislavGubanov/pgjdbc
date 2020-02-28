/*
 * Copyright (c) 2019, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

import com.github.spotbugs.SpotBugsTask
import com.github.vlsi.gradle.crlf.CrLfSpec
import com.github.vlsi.gradle.crlf.LineEndings
import com.github.vlsi.gradle.git.FindGitAttributes
import com.github.vlsi.gradle.properties.dsl.props
import com.github.vlsi.gradle.properties.dsl.stringProperty

plugins {
    publishing
    // Verification
    checkstyle
    jacoco
    id("com.github.autostyle")
    id("com.github.spotbugs")
    id("org.owasp.dependencycheck")
    id("com.github.johnrengelman.shadow") apply false
    // IDE configuration
    id("org.jetbrains.gradle.plugin.idea-ext")
    id("com.github.vlsi.ide")
    // Release
    id("com.github.vlsi.crlf")
    id("com.github.vlsi.gradle-extensions")
    id("com.github.vlsi.license-gather") apply false
    id("com.github.vlsi.stage-vote-release")
}

fun reportsForHumans() = !(System.getenv()["CI"]?.toBoolean() ?: props.bool("CI"))

val lastEditYear = 2019 // TODO: by extra(lastEditYear("$rootDir/LICENSE"))

// Do not enable spotbugs by default. Execute it only when -Pspotbugs is present
val enableSpotBugs = props.bool("spotbugs", default = false)
val skipCheckstyle by props()
val skipAutostyle by props()
val skipJavadoc by props()
val enableMavenLocal by props()
val enableGradleMetadata by props()
val includeTestTags by props("") // e.g. !org.postgresql.test.SlowTests
// By default use Java implementation to sign artifacts
// When useGpgCmd=true, then gpg command line tool is used for signing
val useGpgCmd by props()
val slowSuiteLogThreshold = stringProperty("slowSuiteLogThreshold")?.toLong() ?: 0
val slowTestLogThreshold = stringProperty("slowTestLogThreshold")?.toLong() ?: 2000
val jacocoEnabled by extra {
    props.bool("coverage") || gradle.startParameter.taskNames.any { it.contains("jacoco") }
}

ide {
    // TODO: set copyright to PostgreSQL Global Development Group
    // copyrightToAsf()
    ideaInstructionsUri =
        uri("https://github.com/pgjdbc/pgjdbc")
    doNotDetectFrameworks("android", "jruby")
}

// This task scans the project for gitignore / gitattributes, and that is reused for building
// source/binary artifacts with the appropriate eol/executable file flags
// It enables to automatically exclude patterns from .gitignore
val gitProps by tasks.registering(FindGitAttributes::class) {
    // Scanning for .gitignore and .gitattributes files in a task avoids doing that
    // when distribution build is not required (e.g. code is just compiled)
    root.set(rootDir)
}

val String.v: String get() = rootProject.extra["$this.version"] as String

val buildVersion = "pgjdbc".v + releaseParams.snapshotSuffix

println("Building pgjdbc $buildVersion")

val isReleaseVersion = rootProject.releaseParams.release.get()

// Configures URLs to SVN and Nexus

val licenseHeaderFile = file("config/license.header.java")

val jacocoReport by tasks.registering(JacocoReport::class) {
    group = "Coverage reports"
    description = "Generates an aggregate report from all subprojects"
}

allprojects {
    group = "org.postgresql"
    version = buildVersion

    apply(plugin = "com.github.vlsi.gradle-extensions")

    repositories {
        if (enableMavenLocal) {
            mavenLocal()
        }
        mavenCentral()
    }

    val javaUsed = file("src/main/java").isDirectory
    if (javaUsed) {
        apply(plugin = "java-library")
        if (jacocoEnabled) {
            apply(plugin = "jacoco")
        }
    }

    plugins.withId("java-library") {
        dependencies {
            "implementation"(platform(project(":bom")))
        }
    }

    val hasTests = file("src/test/java").isDirectory || file("src/test/kotlin").isDirectory
    if (hasTests) {
        // Add default tests dependencies
        dependencies {
            val testImplementation by configurations
            val testRuntimeOnly by configurations
            testImplementation("org.junit.jupiter:junit-jupiter-api")
            testImplementation("org.junit.jupiter:junit-jupiter-params")
            testImplementation("org.hamcrest:hamcrest")
            testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
            if (project.props.bool("junit4", default = true)) {
                // Allow projects to opt-out of junit dependency, so they can be JUnit5-only
                testImplementation("junit:junit")
                testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
            }
        }
    }

    if (!skipAutostyle) {
        apply(plugin = "com.github.autostyle")
        autostyle {
            kotlinGradle {
                ktlint()
                trimTrailingWhitespace()
                endWithNewline()
            }
            format("markdown") {
                target("**/*.md")
                endWithNewline()
            }
        }
    }
    if (!skipCheckstyle) {
        apply<CheckstylePlugin>()
        dependencies {
            checkstyle("com.puppycrawl.tools:checkstyle:${"checkstyle".v}")
        }
        checkstyle {
            // Current one is ~8.8
            // https://github.com/julianhyde/toolbox/issues/3
            isShowViolations = true
            // TOOD: move to /config
            configDirectory.set(File(rootDir, "pgjdbc/src/main/checkstyle"))
            configFile = configDirectory.get().file("checks.xml").asFile
            configProperties = mapOf(
                "base_dir" to rootDir.toString()
            )
        }
        tasks.register("checkstyleAll") {
            dependsOn(tasks.withType<Checkstyle>())
        }
    }
    if (!skipAutostyle || !skipCheckstyle) {
        tasks.register("style") {
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            description = "Formats code (license header, import order, whitespace at end of line, ...) and executes Checkstyle verifications"
            if (!skipAutostyle) {
                dependsOn("autostyleApply")
            }
            if (!skipCheckstyle) {
                dependsOn("checkstyleAll")
            }
        }
    }

    tasks.withType<AbstractArchiveTask>().configureEach {
        // Ensure builds are reproducible
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        dirMode = "775".toInt(8)
        fileMode = "664".toInt(8)
    }

    plugins.withType<SigningPlugin> {
        afterEvaluate {
            configure<SigningExtension> {
                val release = rootProject.releaseParams.release.get()
                // Note it would still try to sign the artifacts,
                // however it would fail only when signing a RELEASE version fails
                isRequired = release
                if (useGpgCmd) {
                    useGpgCmd()
                }
            }
        }
    }

    plugins.withType<JacocoPlugin> {
        the<JacocoPluginExtension>().toolVersion = "jacoco".v

        val testTasks = tasks.withType<Test>()
        val javaExecTasks = tasks.withType<JavaExec>()
        // This configuration must be postponed since JacocoTaskExtension might be added inside
        // configure block of a task (== before this code is run). See :src:dist-check:createBatchTask
        afterEvaluate {
            for (t in arrayOf(testTasks, javaExecTasks)) {
                t.configureEach {
                    extensions.findByType<JacocoTaskExtension>()?.apply {
                        // Do not collect coverage when not asked (e.g. via jacocoReport or -Pcoverage)
                        isEnabled = jacocoEnabled
                        // We don't want to collect coverage for third-party classes
                        includes?.add("org.postgresql.*")
                    }
                }
            }
        }

        jacocoReport {
            // Note: this creates a lazy collection
            // Some of the projects might fail to create a file (e.g. no tests or no coverage),
            // So we check for file existence. Otherwise JacocoMerge would fail
            val execFiles =
                    files(testTasks, javaExecTasks).filter { it.exists() && it.name.endsWith(".exec") }
            executionData(execFiles)
        }

        tasks.withType<JacocoReport>().configureEach {
            reports {
                html.isEnabled = reportsForHumans()
                xml.isEnabled = !reportsForHumans()
            }
        }
    }

    tasks {
        withType<Javadoc>().configureEach {
            (options as StandardJavadocDocletOptions).apply {
                // Please refrain from using non-ASCII chars below since the options are passed as
                // javadoc.options file which is parsed with "default encoding"
                noTimestamp.value = true
                showFromProtected()
                // javadoc: error - The code being documented uses modules but the packages
                // defined in https://docs.oracle.com/javase/9/docs/api/ are in the unnamed module
                source = "1.8"
                docEncoding = "UTF-8"
                charSet = "UTF-8"
                encoding = "UTF-8"
                docTitle = "PostgreSQL JDBC ${project.name} API"
                windowTitle = "PostgreSQL JDBC ${project.name} API"
                header = "<b>PostgreSQL JDBC</b>"
                bottom =
                    "Copyright &copy; 1997-$lastEditYear PostgreSQL Global Development Group. All Rights Reserved."
                if (JavaVersion.current() >= JavaVersion.VERSION_1_9) {
                    addBooleanOption("html5", true)
                    links("https://docs.oracle.com/javase/9/docs/api/")
                } else {
                    links("https://docs.oracle.com/javase/8/docs/api/")
                }
            }
        }
    }

    plugins.withType<JavaPlugin> {
        configure<JavaPluginConvention> {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }

        val sourceSets: SourceSetContainer by project

        apply(plugin = "signing")
        apply(plugin = "maven-publish")

        if (!enableGradleMetadata) {
            tasks.withType<GenerateModuleMetadata> {
                enabled = false
            }
        }

        if (isReleaseVersion) {
            configure<SigningExtension> {
                // Sign all the publications
                sign(publishing.publications)
            }
        }

        if (!skipAutostyle) {
            autostyle {
                java {
                    // targetExclude("**/test/java/*.java")
                    // TODO: implement license check (with copyright year)
                    // licenseHeaderFile(licenseHeaderFile)
                    importOrder(
                        "static ",
                        "org.postgresql.",
                        "",
                        "java.",
                        "javax."
                    )
                    removeUnusedImports()
                    trimTrailingWhitespace()
                    indentWithSpaces(4)
                    endWithNewline()
                }
            }
        }
        if (jacocoEnabled) {
            // Add each project to combined report
            val mainCode = sourceSets["main"]
            jacocoReport.configure {
                additionalSourceDirs.from(mainCode.allJava.srcDirs)
                sourceDirectories.from(mainCode.allSource.srcDirs)
                classDirectories.from(mainCode.output)
            }
        }
        if (enableSpotBugs) {
            apply(plugin = "com.github.spotbugs")
            spotbugs {
                toolVersion = "spotbugs".v
                reportLevel = "high"
                //  excludeFilter = file("$rootDir/src/main/config/spotbugs/spotbugs-filter.xml")
                // By default spotbugs verifies TEST classes as well, and we do not want that
                this.sourceSets = listOf(sourceSets["main"])
            }
            dependencies {
                // Parenthesis are needed here: https://github.com/gradle/gradle/issues/9248
                (constraints) {
                    "spotbugs"("org.ow2.asm:asm:${"asm".v}")
                    "spotbugs"("org.ow2.asm:asm-all:${"asm".v}")
                    "spotbugs"("org.ow2.asm:asm-analysis:${"asm".v}")
                    "spotbugs"("org.ow2.asm:asm-commons:${"asm".v}")
                    "spotbugs"("org.ow2.asm:asm-tree:${"asm".v}")
                    "spotbugs"("org.ow2.asm:asm-util:${"asm".v}")
                }
            }
        }

        (sourceSets) {
            "main" {
                resources {
                    // TODO: remove when LICENSE is removed (it is used by Maven build for now)
                    exclude("src/main/resources/META-INF/LICENSE")
                }
            }
        }

        tasks {
            withType<Jar>().configureEach {
                manifest {
                    attributes["Bundle-License"] = "Apache-2.0"
                    attributes["Implementation-Title"] = "PostgreSQL JDBC Driver"
                    attributes["Implementation-Version"] = "4.2" // TODO: JDBC spec version
                    attributes["Specification-Vendor"] = "Oracle Corporation"
                    attributes["Specification-Version"] = "4.2" // TODO: JDBC spec version
                    attributes["Specification-Title"] = "JDBC"
                    attributes["Implementation-Vendor"] = "PostgreSQL Global Development Group"
                    attributes["Implementation-Vendor-Id"] = "org.postgresql"
                }
            }

            withType<JavaCompile>().configureEach {
                options.encoding = "UTF-8"
            }
            withType<Test>().configureEach {
                useJUnitPlatform {
                    if (includeTestTags.isNotBlank()) {
                        includeTags.add(includeTestTags)
                    }
                }
                testLogging {
                    showStandardStreams = true
                }
                exclude("**/*Suite*")
                jvmArgs("-Xmx1536m")
                jvmArgs("-Djdk.net.URLClassPath.disableClassPathURLCheck=true")
                // Pass the property to tests
                fun passProperty(name: String, default: String? = null) {
                    val value = System.getProperty(name) ?: default
                    value?.let { systemProperty(name, it) }
                }
                passProperty("java.awt.headless")
                passProperty("junit.jupiter.execution.parallel.enabled", "true")
                passProperty("junit.jupiter.execution.timeout.default", "5 m")
                passProperty("user.language", "TR")
                passProperty("user.country", "tr")
                val props = System.getProperties()
                for (e in props.propertyNames() as `java.util`.Enumeration<String>) {
                    if (e.startsWith("pgjdbc.")) {
                        passProperty(e)
                    }
                }
                for (p in listOf("server", "port", "database", "username", "password",
                        "privilegedUser", "privilegedPassword",
                        "simpleProtocolOnly", "enable_ssl_tests")) {
                    passProperty(p)
                }
            }
            withType<SpotBugsTask>().configureEach {
                group = LifecycleBasePlugin.VERIFICATION_GROUP
                if (enableSpotBugs) {
                    description = "$description (skipped by default, to enable it add -Dspotbugs)"
                }
                reports {
                    html.isEnabled = reportsForHumans()
                    xml.isEnabled = !reportsForHumans()
                }
                enabled = enableSpotBugs
            }

            afterEvaluate {
                // Add default license/notice when missing
                withType<Jar>().configureEach {
                    CrLfSpec(LineEndings.LF).run {
                        into("META-INF") {
                            filteringCharset = "UTF-8"
                            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
                            // Note: we need "generic Apache-2.0" text without third-party items
                            // So we use the text from $rootDir/config/ since source distribution
                            // contains altered text at $rootDir/LICENSE
                            textFrom("$rootDir/src/main/config/licenses/LICENSE")
                            textFrom("$rootDir/NOTICE")
                        }
                    }
                }
            }
        }

        // Note: jars below do not normalize line endings.
        // Those jars, however are not included to source/binary distributions
        // so the normailzation is not that important
        val sourcesJar by tasks.registering(Jar::class) {
            from(sourceSets["main"].allJava)
            archiveClassifier.set("sources")
        }

        val javadocJar by tasks.registering(Jar::class) {
            from(tasks.named(JavaPlugin.JAVADOC_TASK_NAME))
            archiveClassifier.set("javadoc")
        }

        val archives by configurations.getting

        // Parenthesis needed to use Project#getArtifacts
        (artifacts) {
            archives(sourcesJar)
        }

        configure<PublishingExtension> {
            if (project.path == ":") {
                // Do not publish "root" project. Java plugin is applied here for DSL purposes only
                return@configure
            }
            if (!project.props.bool("nexus.publish", default = true)) {
                // Some of the artifacts do not need to be published
                return@configure
            }
            publications {
                create<MavenPublication>(project.name) {
                    artifactId = project.name
                    version = rootProject.version.toString()
                    description = project.description
                    from(components["java"])

                    if (!skipJavadoc) {
                        // Eager task creation is required due to
                        // https://github.com/gradle/gradle/issues/6246
                        artifact(sourcesJar.get())
                        artifact(javadocJar.get())
                    }

                    // Use the resolved versions in pom.xml
                    // Gradle might have different resolution rules, so we set the versions
                    // that were used in Gradle build/test.
                    versionMapping {
                        usage(Usage.JAVA_RUNTIME) {
                            fromResolutionResult()
                        }
                        usage(Usage.JAVA_API) {
                            fromResolutionOf("runtimeClasspath")
                        }
                    }
                    pom {
                        withXml {
                            val sb = asString()
                            var s = sb.toString()
                            // <scope>compile</scope> is Maven default, so delete it
                            s = s.replace("<scope>compile</scope>", "")
                            // Cut <dependencyManagement> because all dependencies have the resolved versions
                            s = s.replace(
                                Regex(
                                    "<dependencyManagement>.*?</dependencyManagement>",
                                    RegexOption.DOT_MATCHES_ALL
                                ),
                                ""
                            )
                            sb.setLength(0)
                            sb.append(s)
                            // Re-format the XML
                            asNode()
                        }
                        name.set(
                            (project.findProperty("artifact.name") as? String) ?: "pgdjbc ${project.name.capitalize()}"
                        )
                        description.set(project.description ?: "PostgreSQL JDBC Driver ${project.name.capitalize()}")
                        inceptionYear.set("1997")
                        url.set("https://jdbc.postgresql.org")
                        licenses {
                            license {
                                name.set("BSD-2-Clause")
                                url.set("https://jdbc.postgresql.org/about/license.html")
                                comments.set("BSD-2-Clause, copyright PostgreSQL Global Development Group")
                                distribution.set("repo")
                            }
                        }
                        issueManagement {
                            system.set("GitHub issues")
                            url.set("https://github.com/pgjdbc/pgjdbc/issues")
                        }
                        mailingLists {
                            mailingList {
                                name.set("PostgreSQL JDBC development list")
                                subscribe.set("https://lists.postgresql.org/")
                                unsubscribe.set("https://lists.postgresql.org/unsubscribe/")
                                post.set("pgsql-jdbc@postgresql.org")
                                archive.set("https://www.postgresql.org/list/pgsql-jdbc/")
                            }
                        }
                        scm {
                            connection.set("scm:git:https://github.com/pgjdbc/pgjdbc.git")
                            developerConnection.set("scm:git:https://github.com/pgjdbc/pgjdbc.git")
                            url.set("https://github.com/pgjdbc/pgjdbc")
                            tag.set("HEAD")
                        }
                    }
                }
            }
        }
    }
}