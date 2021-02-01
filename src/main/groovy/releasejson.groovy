def updateReleaseJson(json, tag, createNewBranch, releaseJsonBranch, dryRun) {
    node {
        ws {
            sh "rm -rf *"
            sh "rm -rf .git"
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

            stage "Update release.json"
            println(json)
            writeFile file: 'release.json', text: json

            sh "git add --update"
            if (tag != null) {
                sh "git commit -m 'release(${tag})'"
                stage "Tag release.json ${tag}"
                sh "git tag ${tag}"
            } else {
                sh "git commit -m 'prepareRelease(): update version'"
            }

            if (createNewBranch != null && !dryRun) {
                sh "git push origin ${releaseJsonBranch}:${createNewBranch}"
            }

            if (!dryRun) {
                sh "git push --tags origin ${releaseJsonBranch}"
            }
        }
    }
}

return this