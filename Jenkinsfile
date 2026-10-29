pipeline {
    agent { label 'student-laptop' }
    options {
        skipDefaultCheckout(true)
        disableConcurrentBuilds()
        timeout(time: 5, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '5'))
    }
    triggers {
        pollSCM('* * * * *')
    }
    stages {
        stage('Checkout') {
            steps {
                checkout scm
                dir('build') {
                    deleteDir()
                }
                script {
                    if (isUnix()) {
                        sh 'git log -1 --oneline && java -version && javac -version'
                    } else {
                        bat 'git log -1 --oneline && java -version && javac -version'
                    }
                }
            }
        }
        stage('Submission setup') {
            steps {
                script {
                    if (isUnix()) {
                        sh 'java ci/BuildEvidence.java prepare'
                    } else {
                        bat 'java ci/BuildEvidence.java prepare'
                    }
                }
            }
        }
        stage('Test') {
            steps {
                script {
                    if (isUnix()) {
                        sh 'java ci/Build.java test'
                    } else {
                        bat 'java ci/Build.java test'
                    }
                }
            }
            post {
                always {
                    junit testResults: 'build/test-results/TEST-FestPass.xml'
                }
            }
        }
        stage('Build JAR') {
            steps {
                script {
                    if (isUnix()) {
                        sh 'java ci/Build.java package'
                    } else {
                        bat 'java ci/Build.java package'
                    }
                }
            }
        }
        stage('Smoke test JAR') {
            steps {
                script {
                    if (isUnix()) {
                        sh 'java ci/SmokeTest.java'
                    } else {
                        bat 'java ci/SmokeTest.java'
                    }
                }
            }
        }
        stage('Save artifact') {
            steps {
                archiveArtifacts artifacts: 'build/festpass.jar,build/build-info.txt,build/smoke-test.txt',
                                 fingerprint: true
            }
        }
    }
    post {
        always {
            script {
                withEnv(["ASSESSMENT_RESULT=${currentBuild.currentResult}"]) {
                    if (isUnix()) {
                        sh 'java ci/BuildEvidence.java "$ASSESSMENT_RESULT"'
                    } else {
                        bat 'java ci/BuildEvidence.java "%ASSESSMENT_RESULT%"'
                    }
                }
            }
            archiveArtifacts artifacts: 'build/submission-evidence.csv', fingerprint: true
        }
    }
}
