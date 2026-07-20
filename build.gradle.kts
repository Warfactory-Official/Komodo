plugins {
    id("dev.prism")
}

group = "com.norwood"
version = "1.2.0"

prism {
    metadata {
        modId = "komodo"
        name = "Komodo SBW renderer"
        description = "GPU-instanced Flywheel rendering for Superb Warfare vehicles."
        license = "GPL-3.0"
    }

    curseMaven()
    maven("createmod", "https://maven.createmod.net/")
    maven("kotlinforforge", "https://thedarkcolour.github.io/KotlinForForge/")

    version("1.21.1") {
        neoforge {
            loaderVersion = "21.1.222"
            loaderVersionRange = "[21.1,)"

            dependencies {
                modImplementation("curse.maven:superb-warfare-1218165:8104860")
                modRuntimeOnly("curse.maven:superb-warfare-1218165:8104860")
                modImplementation("curse.maven:geckolib-388172:8350073")
                modCompileOnly("dev.engine-room.flywheel:flywheel-neoforge-api-1.21.1:1.0.6-42")
                jarJar("dev.engine-room.flywheel:flywheel-neoforge-1.21.1:1.0.6-42")
                modRuntimeOnly("dev.engine-room.flywheel:flywheel-neoforge-1.21.1:1.0.6-42")
                runtimeOnly("thedarkcolour:kotlinforforge-neoforge:5.6.0")
            }
        }
    }

    version("1.20.1") {
        forge {
            loaderVersion = "47.4.18"
            loaderVersionRange = "[47,)"

            dependencies {
                modImplementation("curse.maven:superb-warfare-1218165:8104849")
                modRuntimeOnly("curse.maven:curios-309927:6418456")
                modImplementation("curse.maven:geckolib-388172:8285794")
                modCompileOnly("dev.engine-room.flywheel:flywheel-forge-api-1.20.1:1.0.4")
                jarJar("dev.engine-room.flywheel:flywheel-forge-1.20.1:1.0.4")
                modRuntimeOnly("dev.engine-room.flywheel:flywheel-forge-1.20.1:1.0.4")
                runtimeOnly("thedarkcolour:kotlinforforge:4.11.0")
            }
        }
    }
}
