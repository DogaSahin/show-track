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

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
