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
				    executeGradleTask("clean build")
				}
            }
        }
    }
    post {
        always {
            junit 'build/test-results/**/*.xml'
            jacoco()
        }
    }
}

def executeGradleTask(String task) {
        sh "gradle ${task} -PmavenUser=$MAVEN_CREDS_USR -PmavenPassword=$MAVEN_CREDS_PSW"
}