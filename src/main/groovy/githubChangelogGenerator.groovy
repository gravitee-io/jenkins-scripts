import groovy.json.JsonSlurper

println 'Generating changelog for version ' + System.getProperties().getProperty('MILESTONE_VERSION') + '...'

String originChangelog = readFile('CHANGELOG.md').replace('# Change Log', '')

String changelog = '# Change Log\n\n'

// get milestones from version
List milestones = new ArrayList()

for (int i = 1; i <= 100; i++) {
    def pageMilestones = new JsonSlurper().parseText(
            new URL('https://api.github.com/repos/gravitee-io/issues/milestones?state=closed&page=' + i).text)
    if (!pageMilestones) {
        break
    }
    milestones.addAll(pageMilestones)
}

def milestone = milestones.find { it.title ==  System.getProperties().getProperty('MILESTONE_VERSION') }

if (milestone) {
    int milestoneNumber = milestone.number

    String milestoneDate = milestone.closed_at

    println 'Generating changelog for version ' +  System.getProperties().getProperty('MILESTONE_VERSION') + ' / milestone ' + milestoneNumber + '...'

    List issues = new ArrayList()

    for (int i = 1; i <= 100; i++) {
        def pageIssues = new JsonSlurper().parseText(
                new URL('https://api.github.com/repos/gravitee-io/issues/issues?state=closed&milestone=' + milestoneNumber + '&page=' + i).text)
        if (!pageIssues) {
            break
        }
        issues.addAll(pageIssues)
    }

    // exclusion of duplicates and technicals
    issues = issues.findAll {
        !it.labels.name.contains('type: duplicate') && !it.labels.name.contains('type: technical')
    }

    println issues.size + ' issues found'

    changelog += '## [' +  System.getProperties().getProperty('MILESTONE_VERSION') + ' (' + milestoneDate.substring(0, 10) + ')](https://github.com/gravitee-io/issues/milestone/' + milestoneNumber + '?closed=1)\n'

    // Bug Fixes part
    changelog += generateChangelogPart(issues, 'Bug fixes', 'type: bug')

    // Features part
    changelog += generateChangelogPart(issues, 'Features', 'type: feature')

    // Features part
    changelog += generateChangelogPart(issues, 'Improvements', 'type: enhancement')

    changelog += originChangelog

    writeFile file: 'CHANGELOG.md', text: changelog
} else {
    println 'Unknown version ' +  System.getProperties().getProperty('MILESTONE_VERSION')
}

private String generateChangelogPart(issues, String changelogPartTitle, String type) {
    String changelog = ''

    // filter type
    issues = issues.findAll { it.labels.name.contains(type) }

    println issues.size + ' issues found for the type ' + type

    if (issues) {
        changelog += '******'
        changelog += '\n####' + changelogPartTitle + '\n'
        // group by domain (portal, gateway...)
        Map<String, List> domainIssues = new LinkedHashMap<String, List>()
        for (int i = 0; i < issues.size(); i++) {
            def matcher = issues[i].title =~ '^\\[((\\w|-|_)+)\\]'
            String domain = matcher.size() == 0 ? 'General' : matcher[0][1].capitalize()

            List listIssues = domainIssues.get(domain)

            if (listIssues == null) {
                listIssues = new ArrayList()
            }
            listIssues.add(issues[i])

            domainIssues.put(domain, listIssues)
        }

        domainIssues = domainIssues.sort()

        for (domainIssue in domainIssues.entrySet()) {
            changelog += '\n***' + domainIssue.key + '***\n'

            def iss = domainIssue.value
            List titles = new LinkedList()
            for (int j = 0; j < iss.size(); j++) {
                def title = iss[j].title
                if (iss[j].title.indexOf(']') > 0) {
                    title = title.substring(iss[j].title.indexOf(']') + 2)
                }

                titles.add('\n- ' + title.replace(': ', '').capitalize() + ' [\\#' + iss[j].number + '](' + iss[j].html_url + ')')
            }
            titles = titles.sort()

            for (int j = 0; j < titles.size(); j++) {
                changelog += titles[j]
            }
        }
    }
    return changelog
}

private String readFile(String fileName) {
    return new File(fileName).text
}

private void writeFile(object) {
    new File(object.file).text = object.text
}
