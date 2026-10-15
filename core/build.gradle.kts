plugins { id("org.jetbrains.kotlin.jvm") }
kotlin { jvmToolchain(17) }
dependencies {
    implementation("org.locationtech.jts:jts-core:1.20.0")
    testImplementation("junit:junit:4.13.2")
}
