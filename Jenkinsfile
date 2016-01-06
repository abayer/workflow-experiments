node('master') {
    echo env.class.toString()

    if (env.GITHUB_PR_NUMBER != null) { 
        checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: '*/pull/${GITHUB_PR_NUMBER}/head']], userRemoteConfigs: [[refspec: '+refs/pull/${GITHUB_PR_NUMBER}/head:refs/remotes/origin-pull/pull/${GITHUB_PR_NUMBER}/head', url: 'git://github.com/abayer/workflow-experiments.git']]]
    } else { 
        git url: "git://github.com/abayer/workflow-experiments.git", branch: "master"
    }

    sh "ls"
}
