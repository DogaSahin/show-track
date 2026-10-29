import com.android.build.api.artifact.SingleArtifact
import java.security.MessageDigest

plugins {
    id("showtrack.android.application")
    id("showtrack.android.compose")
    // :app is where @HiltAndroidApp lives, so this is the module whose KSP run assembles the
    // singleton component out of every @InstallIn module on the runtime classpath.
    id("showtrack.android.hilt")
}

// The oldest versionCode still allowed to run. Raise it only for a release that older installs must
// not keep using: the app then makes the update mandatory instead of dismissable.
val minVersionCode = 1

// Release signing comes from ~/.gradle/gradle.properties (or -P), never from the repo.
val releaseSigningKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val releaseSigningValues =
    releaseSigningKeys.associateWith {
        providers.gradleProperty("showtrack.release.$it").orNull
    }
val releaseSigningConfigured = releaseSigningValues.values.all { !it.isNullOrBlank() }

/** Copies the signed release APK next to a `release.json` describing it, ready to upload. */
abstract class WriteReleaseInfo : DefaultTask() {
    @get:InputDirectory
    abstract val apkDir: DirectoryProperty

    @get:Input
    abstract val versionCode: Property<Int>

    @get:Input
    abstract val versionName: Property<String>

    @get:Input
    abstract val minVersionCode: Property<Int>

    @get:Input
    abstract val notes: Property<String>

    @get:OutputDirectory
    abstract val outDir: DirectoryProperty

    @TaskAction
    fun write() {
        val apk =
            apkDir
                .get()
                .asFile
                .listFiles { f -> f.extension == "apk" }
                .orEmpty()
                .singleOrNull()
                ?: error("Expected exactly one release APK in ${apkDir.get().asFile}")
        val out = outDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val name = "showtrack-${versionCode.get()}.apk"
        val copy = apk.copyTo(out.resolve(name))
        val sha256 =
            MessageDigest
                .getInstance("SHA-256")
                .digest(copy.readBytes())
                .joinToString("") { "%02x".format(it) }

        fun quote(s: String) =
            buildString {
                append('"')
                for (c in s) {
                    when (c) {
                        '"' -> append("\\\"")
                        '\\' -> append("\\\\")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> append(c)
                    }
                }
                append('"')
            }
        out.resolve("release.json").writeText(
            """
            {
              "version_code": ${versionCode.get()},
              "version_name": ${quote(versionName.get())},
              "min_version_code": ${minVersionCode.get()},
              "apk": ${quote(name)},
              "sha256": "$sha256",
              "notes": ${quote(notes.get())}
            }
            """.trimIndent() + "\n",
        )
    }
}

android {
    namespace = "com.anarky.showtrack"

    // BuildConfig.VERSION_CODE is what the update check compares against the server's release.
    buildFeatures {
        buildConfig = true
    }

    testOptions {
        // NavGraphRegistrationTest builds a REAL NavGraph on the JVM under Robolectric —
        // NavDestination parses its route into a deep link, which needs android.net.Uri — and
        // Robolectric cannot load the merged manifest/resources it shadows without this.
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    defaultConfig {
        applicationId = "com.anarky.showtrack"
        versionCode = 2
        versionName = "1.1"
        check(minVersionCode <= versionCode!!) {
            "minVersionCode ($minVersionCode) must not exceed versionCode ($versionCode)"
        }
    }

    signingConfigs {
        create("release") {
            if (releaseSigningConfigured) {
                storeFile = file(releaseSigningValues.getValue("storeFile")!!)
                storePassword = releaseSigningValues.getValue("storePassword")
                keyAlias = releaseSigningValues.getValue("keyAlias")
                keyPassword = releaseSigningValues.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (releaseSigningConfigured) signingConfig = signingConfigs.getByName("release")
            optimization {
                enable = false
            }
        }
    }
}

val checkReleaseSigning by tasks.registering {
    val configured = releaseSigningConfigured
    doLast {
        check(configured) {
            "Release signing is not configured: set showtrack.release.storeFile, storePassword, " +
                "keyAlias and keyPassword in ~/.gradle/gradle.properties"
        }
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val output = variant.outputs.single()
        val minCode = minVersionCode
        val writeReleaseInfo =
            tasks.register<WriteReleaseInfo>("writeReleaseInfo") {
                apkDir.set(variant.artifacts.get(SingleArtifact.APK))
                versionCode.set(output.versionCode.map { it })
                versionName.set(output.versionName.map { it })
                minVersionCode.set(minCode)
                notes.set(providers.gradleProperty("showtrack.release.notes").orElse(""))
                outDir.set(layout.buildDirectory.dir("release-out"))
            }
        tasks.matching { it.name == "assembleRelease" }.configureEach {
            dependsOn(checkReleaseSigning)
            finalizedBy(writeReleaseInfo)
        }
    }
}

dependencies {
    implementation(project(":core:designsystem"))

    // :app may depend on :core:network — architecture rule 2 constrains :feature:* modules, not
    // this one. Two uses, both belonging to the composition root: the @PlainClient qualifier that
    // ShowTrackApplication hands Coil, and (from the test source set, which sees this same
    // classpath) the DataStore file name the backup-exclusion rules in res/xml have to match.
    implementation(project(":core:network"))

    // The route contract the NavHost is written against, and the data layer's re-exposed
    // `authEvents`. :core:data rather than :core:network's AuthEventBus directly — see
    // MainActivity.authEventSource for why the composition root holds itself to rule 2's story
    // even though the build does not make it.
    implementation(project(":core:navigation"))
    implementation(project(":core:data"))

    // Feature modules are pulled in here and nowhere else — that is what makes rule 1 (features
    // never depend on each other) possible at all. All nine, because :app is the only module that
    // may name more than one: it is where the nav graph is stitched.
    implementation(project(":feature:auth"))
    implementation(project(":feature:detail"))
    implementation(project(":feature:discover"))
    implementation(project(":feature:favorites"))
    implementation(project(":feature:feed"))
    implementation(project(":feature:groups"))
    implementation(project(":feature:library"))
    implementation(project(":feature:profile"))
    implementation(project(":feature:search"))

    // Coil's singleton loader is configured in ShowTrackApplication, so the artifacts that
    // :core:designsystem uses to RENDER images are also needed here to CONFIGURE them.
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.navigation.compose)

    // hiltViewModel(), for AppViewModel — the startup half of the auth gate — resolved directly
    // in ShowTrackNavHost rather than inside a NavBackStackEntry scope, since it decides the
    // graph's own start destination and therefore must resolve before any destination exists.
    // Every :feature:* module gets this from showtrack.android.feature; :app applies
    // showtrack.android.application instead, so it is declared here explicitly.
    implementation(libs.androidx.hilt.navigation.compose)
    // The Application hands WorkManager Hilt's worker factory, so episode-alert workers get their
    // dependencies injected (the workers themselves live in :feature:profile).
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    // collectAsStateWithLifecycle for AppViewModel.start, same reasoning.
    implementation(libs.androidx.lifecycle.runtime.compose)

    // AppRoute::class.sealedSubclasses throws KotlinReflectionNotSupportedError without this, and
    // nothing in the build pulls it in transitively. Test-only on purpose: the route set is
    // enumerated to CHECK the graph, never to build it, so kotlin-reflect never reaches :app's
    // runtime classpath.
    testImplementation(libs.kotlin.reflect)
    // A real NavGraph needs android.net.Uri (route -> deep link) and a Context, so the
    // registration test runs on the JVM under Robolectric rather than as an androidTest CI can
    // only compile. Same call :core:network, :core:data and :core:database already made.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    // ShowTrackGraphRebuildTest composes a real NavHost on the JVM under Robolectric — the only
    // way to exercise NavController.setGraph's graph-inequality branch (task 9b.0, review finding
    // 1) — the same Compose-test setup :feature:library's LibraryEntryHiltTest and
    // :core:designsystem's StatusPresentationTest already use. No Hilt here: the marker
    // destinations that test registers resolve no @HiltViewModel, so :app's lack of a Hilt test
    // harness is not in the way for this one.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // TestNavHostController, for the same reason :feature:library's LibraryEntryHiltTest needs it.
    testImplementation(libs.androidx.navigation.testing)

    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
