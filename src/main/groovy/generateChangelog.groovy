node {
    ws {
        sh 'rm -rf *'
        sh 'rm -rf .git'
        git url: 'git@github.com:gravitee-io/issues.git', branch: 'master'

        sh 'docker run --env MILESTONE=' + ${MILESTONE_VERSION} + ' -v $PWD:/data graviteeio/changelog'

        sh 'git add --update'
        sh 'git commit -m \'Generate changelog for version \'' + ${MILESTONE_VERSION}

        echo readFile('CHANGELOG.md')

        if (!${dryRun}) {
            sh "git push --tags origin master"
        }
    }
}
