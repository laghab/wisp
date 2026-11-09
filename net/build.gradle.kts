plugins {
    alias(libs.plugins.kotlin.jvm)
}

// The only module that may use the network (design.md D9). Plain Kotlin with no Android code, so it is tested on the desktop against a
// real local HTTP server. The app uses it from the Downloads screen only.
kotlin { jvmToolchain(17) }

dependencies {
    compileOnly(libs.json)        // org.json: the Android runtime provides it
    testImplementation(libs.json)
    testImplementation(libs.junit)
}
