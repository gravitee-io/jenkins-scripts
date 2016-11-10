node {
    sh "rm -rf *"
    sh "rm -rf .git"
    checkout([
            $class: 'GitSCM',
            branches: [[name: '*/master']],
            doGenerateSubmoduleConfigurations: false,
            extensions: [[$class: 'LocalBranch', localBranch: 'master']],
            submoduleCfg: [],
            userRemoteConfigs: [[credentialsId: '6a9f959a-dc73-4c4a-a998-049d3c725d34', url: 'git@github.com:gravitee-io/issues.git']]])

    sh "docker run --env MILESTONE_VERSION=${MILESTONE_VERSION} -v '$WORKSPACE':/data graviteeio/changelog"

    echo readFile("CHANGELOG.md")

    sh "git add --update"
    sh "git commit -m \"Generate changelog for version ${MILESTONE_VERSION}\""

    if (!Boolean.valueOf(dryRun)) {
        sh "git push --tags origin master"
    }
}
