import groovy.json.JsonSlurper
import groovy.json.JsonOutput

{ ->
    node {
        dryRunAsBool = Boolean.valueOf(dryRun)
        //git url: 'git@github.com:gravitee-io/release.git', branch: releaseJsonBranch
        checkout([
                $class: 'GitSCM',
                branches: [[ name: "${releaseJsonBranch}" ]],
                doGenerateSubmoduleConfigurations: false,
                extensions: [[
                             $class: 'LocalBranch',
                             localBranch: "${releaseJsonBranch}"
                ]],
                submoduleCfg: [],
                userRemoteConfigs: [[
                                     credentialsId: 'github-gravitee-bot',
                                     url: "git@github.com:gravitee-io/release.git"
                ]]
        ])


        def releaseJSON = readFile encoding: 'UTF-8', file: 'release.json'
        if (dryRunAsBool) {
            println("\n    ##################################" +
                    "\n    #                                #" +
                    "\n    #          DRY RUN MODE          #" +
                    "\n    #                                #" +
                    "\n    ##################################")
        }

        Graviteeio graviteeio = new Graviteeio(parseJson(releaseJSON))

        def MavenReleaser = fileLoader.fromGit(
                'src/main/groovy/releasemaven',
                'https://github.com/gravitee-io/jenkins-scripts.git',
                'master',
                null,
                '')

        Component[] componentsToRelease = filteredComponentsToRelease(graviteeio, dryRunAsBool)
        releaseComponents(graviteeio, componentsToRelease, graviteeio.buildDependencies, MavenReleaser, releaseJsonBranch, dryRunAsBool)
    }
}()

@NonCPS
def parseJson(json) {
    def jsonSlurper = new JsonSlurper()
    def obj = jsonSlurper.parseText(json)
    obj
}

@NonCPS
def toJson(graviteeio) {
    JsonOutput.prettyPrint(JsonOutput.toJson(graviteeio))
}

def filteredComponentsToRelease(Graviteeio graviteeIO, dryRun) {
    def snapshots = []
    def snapshotsPrintList = "\n"
    for ( int i = 0; i < graviteeIO.components.size(); i++ ) {
        if ( graviteeIO.components[i].version.snapshot ) {
            snapshots.add(graviteeIO.components[i])
            snapshotsPrintList += "     - ${graviteeIO.components[i].name} (${graviteeIO.components[i].version.toString()})\n"
        }
    }
    println("\n    ${snapshots.size()} components need to be released : ${snapshotsPrintList} \n")
    if ( !dryRun ) {
        input "\n    Do you want to release them ?"
    }
    snapshots
}

def releaseComponents(graviteeio, componentsToRelease, buildDependencies, MavenReleaser, releaseJsonBranch, dryRun) {
    if ( componentsToRelease == null || componentsToRelease.size() == 0 )
        return

    def componentsToReleaseAsMap = new LinkedHashMap()
    for ( int i = 0; i < componentsToRelease.size(); i++ ) {
        componentsToReleaseAsMap.put(componentsToRelease[i].name, componentsToRelease[i])
    }

    def parallelBuildGroup = new ArrayList()
    for ( int i = 0; i < buildDependencies.size(); i++ ) {
        def components = new ArrayList()
        for ( int j = 0; j < buildDependencies[i].size(); j++ ) {
            if ( componentsToReleaseAsMap.containsKey(buildDependencies[i][j]) ) {
                components.add(componentsToReleaseAsMap[buildDependencies[i][j]])
            }
        }
        if ( components.size() > 0 ) {
            parallelBuildGroup.add(components)
        }
    }

    boolean updateReleaseJsonFile = false
    boolean releaseReleaseJsonFile = true
    try {
        for (int i = 0; i < parallelBuildGroup.size(); i++) {
            def mavenRelease = MavenReleaser.release(parallelBuildGroup[i], dryRun)

            if (mavenRelease.componentsReleased.size() > 0) {
                updateReleaseJsonFile = true
                graviteeio.updateComponents(mavenRelease.componentsReleased)
            }
            if (mavenRelease.errors.size() > 0) {
                releaseReleaseJsonFile = false
                throw mavenRelease.errors[0]
            }
        }
    } finally {
        def JSONReleaser = fileLoader.fromGit(
                'src/main/groovy/releasejson',
                'https://github.com/gravitee-io/jenkins-scripts.git',
                'master',
                null,
                '')
        graviteeio.buildTimestamp = new Date()
        def tag = null
        def createNewBranch = null

        if (releaseReleaseJsonFile) {
            tag = graviteeio.version.releaseVersion()
            graviteeio.version = new Version(graviteeio.version.releaseVersion())
            if (graviteeio.version.getNextBranchName() != graviteeio.version.getCurrentBranchName()) {
                createNewBranch = graviteeio.version.getNextBranchName()
            }
        }

        if(updateReleaseJsonFile) {
            JSONReleaser.updateReleaseJson(toJson(graviteeio.toJsonObject()), tag, createNewBranch, releaseJsonBranch, dryRun)
        }

        if (releaseReleaseJsonFile) {
            if (graviteeio.version.getCurrentBranchName() == graviteeio.version.getNextBranchName()) {
                graviteeio.version = new Version(graviteeio.version.nextFixSnapshotVersion())
            } else {
                graviteeio.version = new Version(graviteeio.version.nextMinorSnapshotVersion())
            }
            JSONReleaser.updateReleaseJson(toJson(graviteeio.toJsonObject()), null, null, releaseJsonBranch, dryRun)
        }
    }
}


class Graviteeio implements Serializable {
    def name
    def scmSshUrl
    def scmHttpUrl
    def buildTimestamp
    Version version
    def components = []
    String[][] buildDependencies

    Graviteeio(jsonObj) {
        this.name = jsonObj.name
        this.scmHttpUrl = jsonObj.scmHttpUrl
        this.scmSshUrl = jsonObj.scmSshUrl
        this.buildTimestamp = jsonObj.buildTimestamp
        this.version = new Version(jsonObj.version)
        this.buildDependencies = jsonObj.buildDependencies
        if ( jsonObj.components ) {
            for ( int i = 0; i < jsonObj.components.size(); i++ ) {
                components.add(new Component(jsonObj.components[i], this.version))
            }
        }
    }

    def toJsonObject() {
        [
                name: this.name,
                version: this.version.getVersion(),
                buildTimestamp: this.buildTimestamp,
                scmSshUrl: this.scmSshUrl,
                scmHttpUrl: this.scmHttpUrl,
                components: this.getComponentsAsJson(),
                buildDependencies: this.buildDependencies
        ]
    }

    def getComponentsAsJson() {
        def componentsAsJson = []

        for(int i = 0; i < components.size(); i++) {
            componentsAsJson.add([
                name: components[i].name,
                version: components[i].version.getVersion()
            ])
        }

        componentsAsJson
    }

    def updateComponents(updatedComponents) {
        for ( int i = 0; i < updatedComponents.size(); i++ ) {
            for ( int j = 0; j < components.size(); j++ ) {
                if (updatedComponents[i].name.equals(components[j].name)){
                    components[j].version = new Version(updatedComponents[i].version.releaseVersion())
                    break
                }
            }
        }
    }
}

class Component implements Serializable {
    String name
    Version version

    Component(jsonObj, defaultVersion) {
        this.name = jsonObj.name
        if (jsonObj.version == null) {
            this.version = new Version(defaultVersion.version)
        } else {
            this.version = new Version(jsonObj.version)
        }
    }
}

class Version implements Serializable {

    String version
    Integer major
    Integer minor
    Integer fix
    Boolean snapshot

    Version(versionAsString) {
        this.version = versionAsString
        this.snapshot = versionAsString.contains("-SNAPSHOT")
        def semver = versionAsString.split("-SNAPSHOT")[0].split("\\.")
        this.major= semver[0] as Integer
        this.minor= semver[1] as Integer
        this.fix= semver[2] as Integer
    }

    @Override
    String toString() {
        version
    }

    def isMaintenance() {
        return fix > 0
    }

    def releaseVersion() {
        def v = major + "." + minor + "." + fix
        v
    }

    def nextMajorSnapshotVersion() {
        def v = (major + 1) + ".0.0-SNAPSHOT"
        v
    }

    def nextMinorSnapshotVersion() {
        def v = major + "." + (minor + 1) + ".0-SNAPSHOT"
        v
    }

    def nextFixSnapshotVersion() {
        def v = major + "." + minor + "." + (fix + 1) + "-SNAPSHOT"
        v
    }

    def getCurrentBranchName() {
        def b = major + "." + minor + ".x"
        if (snapshot && fix == 0) {
            b = "master"
        }
        b
    }
    def getNextBranchName() {
        def b = major + "." + minor + ".x"
        b
    }
}
