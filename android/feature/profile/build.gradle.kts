plugins {
    id("showtrack.android.feature")
}

android {
    testOptions {
        // PushNotifierTest builds a real Intent and a real Uri under Robolectric, which cannot
        // load the merged manifest/resources it shadows without this. Same call :core:network,
        // :core:data, :core:database and :app already made.
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // LoadingState/ErrorState/StaleDataBanner for the library-stats block (task 9b.5), and
    // UserMediaStatus.label() for its status breakdown — decision C-T: a feature module never
    // re-implements a design-system component.
    implementation(project(":core:designsystem"))

    // ProfileRoute, and the detail deep link the notification tap resolves to — the route
    // contract, never :feature:detail itself (architecture rule 1, and ModuleRules fails the
    // build over it).
    implementation(project(":core:navigation"))

    // The ONLY data dependency a feature module declares. PushRepository is an interface; the
    // Retrofit call and the JSON decode behind it are `implementation`-scoped inside :core:data
    // and are not on this module's compile classpath (architecture rule 2).
    implementation(project(":core:data"))

    // The distributor-facing half: MessagingReceiver, PushEndpoint, PushMessage, and the
    // UnifiedPush entry points the profile screen drives.
    implementation(libs.unifiedpush.connector)

    // NotificationCompat / NotificationManagerCompat / ContextCompat, and androidx.core.net.toUri
    // for the deep-link Uri.
    implementation(libs.androidx.core.ktx)

    // rememberLauncherForActivityResult, for the POST_NOTIFICATIONS request. Not in the feature
    // convention plugin: this is the only screen that asks for a runtime permission.
    implementation(libs.androidx.activity.compose)

    // ProfileScreenTest (task 9b.5, round 1) drives the stateless ProfileScreen() overload
    // through a real Compose test rule on the JVM — the same setup :feature:library/
    // :feature:favorites/:feature:discover use.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    // TestNavHostController, so ProfileEntryHiltTest can drive profileEntry() inside a real graph
    // rather than the stateless ProfileScreen() overload alone (task 9c.0, E-L).
    testImplementation(libs.androidx.navigation.testing)

    // Hilt's test harness — composing a hiltViewModel()-backed screen in a JVM test needs it, the
    // same three lines :feature:library's build file carries.
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)
}
