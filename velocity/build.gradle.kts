dependencies {
    implementation(projects.shared)
    implementation(libs.bstats.velocity)
    compileOnly(libs.velocity)
    annotationProcessor(libs.velocity)
}
tasks.compileJava {
    options.release.set(11)
}