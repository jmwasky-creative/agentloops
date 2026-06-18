import java.time.Duration

plugins {
    kotlin("jvm") version "2.0.21"
    application
}

group = "com.agentsloop"
version = "0.1.0"

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.agentsloop.cli.MainKt")
}

tasks.test {
    useJUnitPlatform()
    timeout.set(Duration.ofMinutes(2))
}

dependencies {
    testImplementation(kotlin("test"))
}
