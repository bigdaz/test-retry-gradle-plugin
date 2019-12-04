import jetbrains.buildServer.configs.kotlin.v2018_2.*
import jetbrains.buildServer.configs.kotlin.v2018_2.DslContext
import jetbrains.buildServer.configs.kotlin.v2018_2.buildFeatures.commitStatusPublisher
import jetbrains.buildServer.configs.kotlin.v2018_2.buildSteps.gradle
import jetbrains.buildServer.configs.kotlin.v2018_2.triggers.vcs

open class TestRetryPluginCrossVersionTest(os: Os) : BuildType({
    testRetryVcs()

    params {
        java8Home(os)
    }

    steps {
        gradle {
            tasks = "clean testAll"
            buildFile = ""
            gradleParams = "-s $useGradleInternalScansServer"
            param("org.jfrog.artifactory.selectedDeployableServer.defaultModuleVersionConfiguration", "GLOBAL")
        }
    }
    
    dependencies{
        snapshot(RelativeId("TestRetryPluginQuickFeedback")) {
            onDependencyFailure = FailureAction.CANCEL
            onDependencyCancel = FailureAction.CANCEL
        }
    }

    agentRequirement(os)
}) {
    constructor(os: Os, init: TestRetryPluginCrossVersionTest.() -> Unit) : this(os) {
        init()
    }
}

object LinuxJava18 : TestRetryPluginCrossVersionTest(Os.linux, {
    name = "CrossVersionTest Linux - Java 1.8"
})

object MacOSJava18 : TestRetryPluginCrossVersionTest(Os.macos, {
    name = "CrossVersionTest MacOS - Java 1.8"
})

object WindowsJava18 : TestRetryPluginCrossVersionTest(Os.windows, {
    name = "CrossVersionTest Windows - Java 1.8"
})