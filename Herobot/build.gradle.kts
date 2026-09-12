plugins {
    id("java") // Enables Java support
    id("application") // Allows you to run the app directly via Gradle
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

group = "com.herobot"
version = "1.0"

repositories {
    mavenCentral() // Fetches your Java libraries from the central repository
}

dependencies {
    // SQLite JDBC Driver for Java
    implementation("org.xerial:sqlite-jdbc:3.45.1.0")

    // Apache Commons Math for Java calculations
    implementation("org.apache.commons:commons-math3:3.6.1")

    // JSON library for Java
    implementation("org.json:json:20240303")
}

application {
    // This tells Gradle which Java class contains your 'public static void main'
    mainClass.set("Chatbot")
}

tasks.jar {
    archiveBaseName.set("Herobot")
    archiveVersion.set("1.0")

    manifest {
        attributes(
            mapOf("Main-Class" to "Chatbot")
        )
    }
}

tasks.named<JavaExec>("run") {
    // This allows your Java app to accept keyboard input in the terminal [cite: 2]
    standardInput = System.`in`
}