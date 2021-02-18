node() {
    sh 'rm -rf *'
    sh 'rm -rf .git'

    def mvnHome = tool 'MVN33'
    def javaHome = tool 'JDK 8'
    withEnv(["PATH+MAVEN=${mvnHome}/bin",
             "M2_HOME=${mvnHome}",
             "JAVA_HOME=${javaHome}"]) {

        checkout([
                $class                           : 'GitSCM',
                branches                         : [[
                                                            name: "${BRANCH}"
                                                    ]],
                doGenerateSubmoduleConfigurations: false,
                extensions                       : [[
                                                            $class     : 'LocalBranch',
                                                            localBranch: "${BRANCH}"
                                                    ]],
                submoduleCfg                     : [],
                userRemoteConfigs                : [[
                                                            credentialsId: 'github-gravitee-bot',
                                                            url          : "git@github.com:gravitee-io/gravitee-parent.git"
                                                    ]]
        ])

        // set version
        sh "mvn -B versions:set -DnewVersion=${RELEASE_VERSION} -DgenerateBackupPoms=false"
        sh "cat pom.xml"
        if (Boolean.valueOf(dryRun)) {
            sh "mvn -B -U clean install"
            sh "mvn enforcer:enforce"
        } else {
            sh "mvn -B -U -P gravitee-release clean deploy"
        }

        // commit, tag the release
        sh "git add --update"
        sh "git commit -m 'release(${RELEASE_VERSION})'"
        sh "git tag ${RELEASE_VERSION}"

        // update next version
        sh "mvn -B versions:set -DnewVersion=${NEXT_SNAPSHOT} -DgenerateBackupPoms=false"

        // commit, tag the snapshot
        sh "git add --update"
        sh "git commit -m 'chore(): Prepare next version'"

        // push
        if ( !Boolean.valueOf(dryRun) ) {
            sh "git push --tags origin master"
        }
    }
}