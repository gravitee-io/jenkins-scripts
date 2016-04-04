import groovy.json.JsonSlurper
{ ->
    node {
        docBranch = DOC_BRANCH
        releaseJsonBranchOrTag = "master".equals(RELEASEJSON_BRANCH_OR_TAG) ? "master" : "refs/tags/${RELEASEJSON_BRANCH_OR_TAG}"
        docReleaseVersion = DOC_RELEASE_VERSION
        docReleaseNextSnapshot = DOC_RELEASE_NEXT_SNAPSHOT
        ws {
            checkout([
                    $class                           : 'GitSCM',
                    branches                         : [[name: "${releaseJsonBranchOrTag}"]],
                    doGenerateSubmoduleConfigurations: false,
                    userRemoteConfigs                : [[url: "git@github.com:gravitee-io/release.git"]]
            ])
            def releaseJSON = readFile encoding: 'UTF-8', file: 'release.json'
            releasejson_as_properties = getReleasejsonAsProperties(parseJson(releaseJSON))
        }

        checkout([
                $class                           : 'GitSCM',
                branches                         : [[
                                                            name: "${docBranch}"
                                                    ]],
                doGenerateSubmoduleConfigurations: false,
                extensions                       : [[
                                                            $class     : 'LocalBranch',
                                                            localBranch: "${docBranch}"
                                                    ]],
                submoduleCfg                     : [],
                userRemoteConfigs                : [[
                                                            credentialsId: 'ce78e461-eab0-44fb-bc8d-15b7159b483d',
                                                            url          : "git@github.com:gravitee-io/gravitee-docs.git"
                                                    ]]
        ])

        writeFile file: 'release.properties', text: releasejson_as_properties

        def mvnHome = tool 'Maven 3.2.2'
        def javaHome = tool 'JDK 8'
        withEnv(["PATH+MAVEN=${mvnHome}/bin",
                 "HOME=/root",
                 "M2_HOME=${mvnHome}",
                 "JAVA_HOME=${javaHome}"]) {

            if ( !"".equals(docReleaseVersion) ) {
                sh "mvn -B versions:set -DnewVersion=${docReleaseVersion} -DgenerateBackupPoms=false"
            }
            sh "cat pom.xml"

            sh "mvn -B -U clean deploy"
            sh "git add --update"

            if ( !"".equals(docReleaseVersion) ) {
                sh "git commit -m 'release(${docReleaseVersion})'"
                sh "git tag ${docReleaseVersion}"
            } else {
                sh "git commit -m 'update release.properties'"
            }

            if ( !"".equals(docReleaseNextSnapshot) ) {
                sh "mvn -B versions:set -DnewVersion=${docReleaseNextSnapshot} -DgenerateBackupPoms=false"
                sh "git add --update"
                sh "git commit -m 'prepareNextRelease(${docReleaseNextSnapshot})'"
            }
            sh "cat pom.xml"


            sh "git push --tags origin ${docBranch}"
        }
    }
}()

@NonCPS
def parseJson(json) {
    def jsonSlurper = new JsonSlurper()
    def obj = jsonSlurper.parseText(json)
    obj
}

def getReleasejsonAsProperties(jsonObj) {
    def releasejson_as_properties = ""
    for ( int i = 0; i < jsonObj.components.size(); i++ ) {
        releasejson_as_properties += "${jsonObj.components[i].name}=${jsonObj.components[i].version}\n"
    }
    releasejson_as_properties
}
