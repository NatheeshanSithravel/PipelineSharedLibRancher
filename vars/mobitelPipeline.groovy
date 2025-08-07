// vars/mobitelPipeline.groovy
/**
 * Mobitel Pipeline Shared Library
 * Supports multiple application types: Angular/Nginx, React/Nginx, Spring Boot, Tomcat WAR
 * 
 * Usage:
 * mobitelPipeline {
 *     appType = 'angular-nginx' // or 'react-nginx', 'springboot', 'tomcat-war', 'react-normal'
 *     appName = 'my-app'
 *     environment = 'stg'
 *     namespace = 'intsys'
 *     exposePort = '80'
 *     harbourSecret = 'harbor-intsys'
 *     // ... other optional parameters
 * }
 */

def call(Map config) {
    pipeline {
        agent none

        environment {
            CIR_USER = 'mobitel'
            CIR_PW = credentials('cir-pw')
        }

        stages {
            stage('Initialize') {
                agent any
                steps {
                    script {
                        env.ENV = config.environment ?: 'stg'
                        env.PROJECT = config.project ?: 'mobitel_pipeline'
                        env.APP_NAME = config.appName
                        env.CIR = "${env.ENV}-docker-reg.mobitel.lk"
                        env.KUB_NAMESPACE = config.namespace ?: 'intsys'
                        env.IMAGE_TAG = "${env.CIR}/${env.PROJECT}/${env.APP_NAME}:${env.ENV}.${env.BUILD_NUMBER}"
                        env.EXPOSE_PORT = config.exposePort ?: getDefaultPort(config.appType)
                        env.HARBOUR_SECRET = config.harbourSecret ?: 'harbor-intsys'

                        env.APP_TYPE = config.appType
                        env.MAVEN_IMAGE = config.mavenImage ?: 'maven:3.9.6-amazoncorretto-21'
                        env.CICD_TOOLS_IMAGE = config.cicdToolsImage ?: 'inovadockerimages/cicdtools:latest'
                        env.TRIVY_IMAGE = config.trivyImage ?: 'aquasec/trivy:latest'

                        env.SONAR_ENABLED = (config.sonarEnabled ?: false).toString()
                        env.SONAR_PROJECT_KEY = config.sonarProjectKey ?: "${env.APP_NAME}"
                        env.SONAR_PROJECT_NAME = config.sonarProjectName ?: "${env.APP_NAME}"

                        env.SUCCESS_EMAIL = config.successEmail ?: 'mobiteldev@mobitel.lk'
                        env.FAILURE_EMAIL = config.failureEmail ?: 'jenkins.notification@mobitel.lk'
                        env.FAILURE_CC = config.failureCC ?: 'mobiteldev@mobitel.lk'

                        env.MEMORY_LIMIT = config.memoryLimit ?: '512Mi'
                        env.CPU_LIMIT = config.cpuLimit ?: ''
                    }
                }
            }

            stage('SonarQube Analysis') {
                when {
                    expression { env.SONAR_ENABLED == 'true' }
                }
                agent any
                steps {
                    runSonarAnalysis()
                }
            }

            stage('Build & Test') {
                when {
                    expression { needsBuild(env.APP_TYPE) }
                }
                agent {
                    docker {
                        image env.MAVEN_IMAGE
                        args '-v /root/.m2:/root/.m2'
                    }
                }
                steps {
                    buildApplication(env.APP_TYPE)
                }
            }

            stage('Build & Push Docker Image') {
                agent any
                steps {
                    buildAndPushDockerImage(env.APP_TYPE)
                }
            }

            stage('Security Scan with Trivy') {
                agent {
                    docker {
                        image env.TRIVY_IMAGE
                        args '--entrypoint="" -v /var/jenkins_home/trivy-reports:/reports -v trivy-cache:/root/.cache/'
                    }
                }
                steps {
                    runTrivyScan()
                }
            }

            stage('Cleanup Local Images') {
                agent any
                steps {
                    cleanupLocalImages()
                }
            }

            stage('Deploy to Kubernetes') {
                agent {
                    docker {
                        image env.CICD_TOOLS_IMAGE
                        args '-v /root/.cert:/root/.cert'
                    }
                }
                steps {
                    deployToKubernetes()
                }
            }

            stage('Extract Git Information') {
                agent any
                steps {
                    extractGitInformation()
                }
            }

            stage('Set Deployment Annotations') {
                agent {
                    docker {
                        image env.CICD_TOOLS_IMAGE
                        args '-v /root/.cert:/root/.cert'
                    }
                }
                steps {
                    setDeploymentAnnotations()
                }
            }
        }

        post {
            success {
                sendNotificationEmail('success')
            }
            failure {
                sendNotificationEmail('failure')
            }
        }
    }
}

// Helper function to determine default ports based on app type
def getDefaultPort(appType) {
    switch(appType) {
        case 'angular-nginx':
        case 'react-nginx':
            return '80'
        case 'springboot':
        case 'tomcat-war':
            return '8080'
        case 'react-normal':
            return '3000'
        default:
            return '80'
    }
}

// Helper function to determine if build stage is needed
def needsBuild(appType) {
    return appType in ['springboot', 'tomcat-war']
}
