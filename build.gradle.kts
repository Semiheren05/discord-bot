plugins {
    java
    application
}

group = "com.semih"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // Discord kütüphanesi. Ses (voice) özelliklerini kullanmadığımız için opus'u dahil etmiyoruz.
    implementation("net.dv8tion:JDA:6.7.0") {
        exclude(module = "opus-java")
    }
    // Konsola düzgün log yazmak için
    implementation("org.slf4j:slf4j-simple:2.0.17")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21) // Java 21 ve üzeri her sürümde çalışır
}

application {
    mainClass.set("com.semih.modbot.Main")
}

// Tüm bağımlılıkları içeren tek dosya: build/libs/ModBot.jar
// Çalıştırmak için: java -jar ModBot.jar
val botJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Bağımlılıklarıyla birlikte tek parça ModBot.jar üretir."
    archiveFileName.set("ModBot.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "com.semih.modbot.Main"
    }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class", "META-INF/versions/*/module-info.class")
}

tasks.build {
    dependsOn(botJar)
}
