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
				    executeGradleTask("clean build javadoc")
				}
            }
        }
    }
    post {
        always {

            publishHTML([allowMissing: false, alwaysLinkToLastBuild: false, keepAll: false, reportDir: 'build/docs/javadoc/', reportFiles: 'index.html', reportName: 'Javadoc'])
            junit 'build/test-results/**/*.xml'
            jacoco exclusionPattern: '**/*Test*.class'
        }
    }
}

def executeGradleTask(String task) {
        sh "gradle ${task} -PmavenUser=$MAVEN_CREDS_USR -PmavenPassword=$MAVEN_CREDS_PSW"
}