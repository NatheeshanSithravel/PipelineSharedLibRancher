// vars/mobitelPipeline.groovy
/**
 * Mobitel Pipeline Shared Library
 * Supports multiple application types: Angular/Nginx, React/Nginx, Spring Boot, Tomcat WAR
 * 
 * Usage:
 * mobitelPipeline {
 *     appType = 'springboot'
 *     appName = 'my-app'
 *     environment = 'stg'
 *     namespace = 'intsys'
 *     exposePort = '8080'
 *     harbourSecret = 'harbor-intsys'
 * }
 */

def call(Closure body) {
    // Evaluate the body block, and collect configuration into the object
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()
    
    // Validate required parameters
    validateConfig(config)
    
    // Set defaults and prepare environment variables
    def pipelineEnv = getPipelineEnvironment(config)
    
    pipeline {
        environment {
            // Core configuration - all must be quoted strings
            ENV = "${pipelineEnv.environment}"
            PROJECT = "${pipelineEnv.project}"
            APP_NAME = "${pipelineEnv.appName}"
            CIR = "${pipelineEnv.environment}-docker-reg.mobitel.lk"
            CIR_USER = 'natheeshshaan@gmail.com'
            CIR_PW = "Qwerty@123"
            KUB_NAMESPACE = "${pipelineEnv.namespace}"
            IMAGE_TAG = "natheeshan/${pipelineEnv.appName}:${pipelineEnv.environment}.${env.BUILD_NUMBER}"
            EXPOSE_PORT = "${pipelineEnv.exposePort}"
            HARBOUR_SECRET = "${pipelineEnv.harbourSecret}"
            
            // Application type specific settings
            APP_TYPE = "${pipelineEnv.appType}"
            MAVEN_IMAGE = "${pipelineEnv.mavenImage}"
            CICD_TOOLS_IMAGE = "${pipelineEnv.cicdToolsImage}"
            TRIVY_IMAGE = "${pipelineEnv.trivyImage}"
            
            // Optional SonarQube settings
            SONAR_ENABLED = "${pipelineEnv.sonarEnabled}"
            SONAR_PROJECT_KEY = "${pipelineEnv.sonarProjectKey}"
            SONAR_PROJECT_NAME = "${pipelineEnv.sonarProjectName}"
            
            // Email configuration
            SUCCESS_EMAIL = "${pipelineEnv.successEmail}"
            FAILURE_EMAIL = "${pipelineEnv.failureEmail}"
            FAILURE_CC = "${pipelineEnv.failureCC}"
            
            // Resource limits
            MEMORY_LIMIT = "${pipelineEnv.memoryLimit}"
            CPU_LIMIT = "${pipelineEnv.cpuLimit}"
        }
        
        agent none
        
        stages {            
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

            stage('SonarQube Analysis') {
                when {
                    expression { env.SONAR_ENABLED == 'true' }
                }
                agent any
                steps {
                    runSonarAnalysis()
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

// Function to prepare environment configuration with defaults
def getPipelineEnvironment(config) {
    return [
        environment: config.environment ?: 'stg',
        project: config.project ?: 'mobitel_pipeline',
        appName: config.appName,
        namespace: config.namespace ?: 'intsys',
        exposePort: config.exposePort ?: getDefaultPort(config.appType),
        harbourSecret: config.harbourSecret ?: 'harbor-intsys',
        appType: config.appType,
        mavenImage: config.mavenImage ?: 'maven:3.9.6-amazoncorretto-21',
        cicdToolsImage: config.cicdToolsImage ?: 'inovadockerimages/cicdtools:latest',
        trivyImage: config.trivyImage ?: 'aquasec/trivy:latest',
        sonarEnabled: config.sonarEnabled ?: false,
        sonarProjectKey: config.sonarProjectKey ?: config.appName,
        sonarProjectName: config.sonarProjectName ?: config.appName,
        successEmail: config.successEmail ?: 'natheeshshaan@gmail.com',
        failureEmail: config.failureEmail ?: 'natheeshshaan@gmail.com',
        failureCC: config.failureCC ?: 'natheeshshaan@gmail.com',
        memoryLimit: config.memoryLimit ?: '512Mi',
        cpuLimit: config.cpuLimit ?: 'null'
    ]
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

// Utility function to validate required parameters
def validateConfig(Map config) {
    def requiredParams = ['appName', 'appType']
    def missingParams = []
    
    requiredParams.each { param ->
        if (!config[param]) {
            missingParams.add(param)
        }
    }
    
    if (missingParams) {
        error("Missing required parameters: ${missingParams.join(', ')}")
    }
    
    def validAppTypes = ['angular-nginx', 'react-nginx', 'springboot', 'tomcat-war', 'react-normal']
    if (!(config.appType in validAppTypes)) {
        error("Invalid appType '${config.appType}'. Valid types: ${validAppTypes.join(', ')}")
    }
}

def runSonarAnalysis() {
    script {
        
        // SonarQube analysis
        def scannerHome = tool 'sonar-scanner'
        withSonarQubeEnv('sonar-server') {
            sh """
                ${scannerHome}/bin/sonar-scanner \
                    -Dsonar.projectKey=${env.SONAR_PROJECT_KEY} \
                    -Dsonar.projectName='${env.SONAR_PROJECT_NAME}' \
                    -Dsonar.junit.reportPaths=target/surefire-reports \
                    -Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml
            """
        }
    }
}



def buildApplication(appType) {
    switch(appType) {
        case 'springboot':
        case 'tomcat-war':
            sh 'mvn clean install -DskipTests'
            sh 'mvn test'
            // Publish JUnit results
            junit 'target/surefire-reports/*.xml'
            break
        default:
            echo "No build step required for ${appType}"
    }
}

def buildAndPushDockerImage(appType) {
    sh '''
        docker login -u ${CIR_USER} -p ${CIR_PW} 
    '''
    
    switch(appType) {
        case 'springboot':
            sh '''
                mkdir -p dockerImage/
                cp Dockerfile dockerImage/
                cp target/*.jar dockerImage/
                docker build --tag=${IMAGE_TAG} dockerImage/.
                docker push ${IMAGE_TAG}
            '''
            break
        case 'tomcat-war':
            sh '''
                mkdir -p dockerImage/
                cp Dockerfile dockerImage/
                cp target/*.war dockerImage/
                docker build --tag=${IMAGE_TAG} dockerImage/.
                docker push ${IMAGE_TAG}
            '''
            break
        case 'angular-nginx':
        case 'react-nginx':
        case 'react-normal':
        default:
            sh '''
                docker build --tag=${IMAGE_TAG} .
                docker push ${IMAGE_TAG}
            '''
            break
    }
}

def runTrivyScan() {
    script {
        sh "trivy image --no-progress --timeout 15m -f table ${env.IMAGE_TAG}"
    }
}

def cleanupLocalImages() {
    sh '''
        docker image rm ${IMAGE_TAG}
        rm -rf dockerImage/
    '''
}

def deployToKubernetes() {
    // Setup kubectl configuration
    sh '''
        mkdir -p /root/.kube/
        cp /root/.cert/${ENV}/config /root/.kube/
    '''
    
    script {
        // Try to update existing deployment, create new one if it doesn't exist
        def isDeployed = sh(
            returnStatus: true,
            script: 'kubectl -n ${KUB_NAMESPACE} set image deployment/${APP_NAME} ${APP_NAME}=${IMAGE_TAG} --record'
        )
        
        if (isDeployed != 0) {
            // Create new deployment and service
            sh '''
                kubectl -n ${KUB_NAMESPACE} create deployment ${APP_NAME} --image=${IMAGE_TAG}
                kubectl -n ${KUB_NAMESPACE} expose deployment ${APP_NAME} --name=${APP_NAME} --port=${EXPOSE_PORT}
            '''
            
            // Apply patches for security and resource management
            applyKubernetesPatches()
        }
    }
}

def applyKubernetesPatches() {
    // Add image pull secrets
    sh '''
        kubectl -n ${KUB_NAMESPACE} patch deployment ${APP_NAME} --patch '{"spec": {"template": {"spec": {"imagePullSecrets": [{"name": "'"${HARBOUR_SECRET}"'"}]}}}}'
    '''
    
    // Set resource limits - handle CPU limit conditionally
    script {
        def resourcePatch = '{"limits": {"memory": "' + env.MEMORY_LIMIT + '"'
        if (env.CPU_LIMIT != 'null' && env.CPU_LIMIT != null && env.CPU_LIMIT != '') {
            resourcePatch += ', "cpu": "' + env.CPU_LIMIT + '"'
        }
        resourcePatch += '}}'
        
        sh """
            kubectl -n \${KUB_NAMESPACE} patch deployment \${APP_NAME} --type='json' -p='[{"op": "add","path": "/spec/template/spec/containers/0/resources","value": ${resourcePatch}}]'
        """
    }
}

def extractGitInformation() {
    script {
        // Get Git URL and committer email
        env.GIT_URL = sh(
            script: 'git config --get remote.origin.url',
            returnStdout: true
        ).trim()
        echo "GIT URL: ${env.GIT_URL}"
        
        env.COMMITTER_EMAIL = sh(
            script: "git log -1 --pretty=format:'%ce'",
            returnStdout: true
        ).trim()
        echo "Committer Email: ${env.COMMITTER_EMAIL}"
    }
}

def setDeploymentAnnotations() {
    // Setup kubectl configuration
    sh '''
        mkdir -p /root/.kube/
        cp /root/.cert/${ENV}/config /root/.kube/
    '''
    
    script {
        sh '''
            #!/bin/bash
            echo "GIT URL: ${GIT_URL}"
            echo "JENKINS URL: ${BUILD_URL}"
            DESCRIPTION="Jenkins URL: ${BUILD_URL}  GIT URL: ${GIT_URL}"

            # Check if the deployment has the field.cattle.io/description annotation
            CURRENT_DESCRIPTION=$(kubectl -n ${KUB_NAMESPACE} get deployment ${APP_NAME} -o jsonpath='{.metadata.annotations.field\\.cattle\\.io/description}')

            if [ -z "$CURRENT_DESCRIPTION" ]; then
                echo "No field.cattle.io/description found. Setting the description."
                kubectl -n ${KUB_NAMESPACE} annotate deployment ${APP_NAME} field.cattle.io/description="${DESCRIPTION}" --overwrite
            else
                echo "field.cattle.io/description already exists: $CURRENT_DESCRIPTION"
            fi
        '''
    }
}

def sendNotificationEmail(status) {
    def subject = "${env.JOB_NAME} - Build ${env.BUILD_NUMBER} - ${status.capitalize()}!"
    def body = """${env.JOB_NAME} - Build ${env.BUILD_NUMBER} - ${status.capitalize()}:
Check console output at ${env.BUILD_URL} to view the results."""
    
    if (status == 'success') {
        mail to: 'natheeshshaan@gmail.com',
             subject: subject,
             body: body
    } else {
        mail to: 'natheeshshaan@gmail.com',
             cc: 'natheeshans.ou@mobitel.lk',
             subject: subject,
             body: body
    }
}
