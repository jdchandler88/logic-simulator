pipeline {
    agent {
        docker {
            image 'gradle:5.4.1-jdk8'
            args '--network="host" -u root'
        }

    }
	environment {
    	MAVEN_CREDS = credentials('mavenCreds')
    	GIT_SSH = "${env.WORKSPACE}/custom_ssh"	//this points to a script that runs ssh. allows us to configure ssh
	}
    stages {
        stage('build') {
            steps {
				script {
				    executeGradleTask("clean build jacocotestreport")
				}
            }
        }
        stage('publish') {
            steps {
                script {
                    if (env.BRANCH_NAME == 'master') {
                    	sshagent (credentials: [ 'jenkins-ssh' ]) {
    						sh 'useradd -rm -d /home/jenkins -s /bin/bash -G sudo -u 1004 jenkins'
    						sh 'git fetch --tags --force'
    						executeGradleTask('release -Prelease.useAutomaticVersion=true')
					    }
						executeGradleTask("release -Prelease.useAutomaticVersion=true --stacktrace")
					} else {
					    executeGradleTask("publish")
				 	}
                }
            }
        }
    }
}

def executeGradleTask(String task) {
        sh "gradle ${task} -PmavenUser=$MAVEN_CREDS_USR -PmavenPassword=$MAVEN_CREDS_PSW"
}