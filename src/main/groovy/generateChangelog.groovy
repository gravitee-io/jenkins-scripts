node {
    sh "rm -rf *"
    sh "rm -rf .git"

    version = "${MILESTONE_VERSION}".split(" - ")
    component = "${version[0]}"
    githubUrl = "${component}" == 'APIM' ? 'git@github.com:gravitee-io/issues.git' : 'git@github.com:gravitee-io/graviteeio-access-management.git'

    checkout([
            $class: 'GitSCM',
            branches: [[name: '*/master']],
            doGenerateSubmoduleConfigurations: false,
            extensions: [[$class: 'LocalBranch', localBranch: 'master']],
            submoduleCfg: [],
            userRemoteConfigs: [[credentialsId: '31afd483-f394-439f-b865-94c413e6465f', url: "${githubUrl}"]]])

    sh "docker run --rm --env MILESTONE_VERSION='${MILESTONE_VERSION}' -v '$WORKSPACE':/data graviteeio/changelog"

    majorVersion = "${version[1]}".substring(0, 1)
    changelogFile = ("${majorVersion}" as Integer) > 2 ? "CHANGELOG-v" + "${majorVersion}" + ".adoc" : "CHANGELOG.adoc"
    echo readFile("${changelogFile}")

    sh "git add --update"
    replacement = "${MILESTONE_VERSION}".replace(" ", "_")
    sh "git commit -m \"Generate changelog for version ${replacement}\""
    sh "git tag \"${replacement}\""

    if (!Boolean.valueOf(dryRun)) {
        sh "git push --tags origin master"
    }
}
