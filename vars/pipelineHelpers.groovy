// vars/pipelineHelpers.groovy
/**
 * Helper functions for the Mobitel Pipeline Shared Library
 */

def runSonarAnalysis() {
    script {
        def scannerHome = tool 'sonarscanner'
        withSonarQubeEnv('sonarserver') {
            sh "${scannerHome}/bin/sonar-scanner -Dsonar.projectKey=${env.SONAR_PROJECT_KEY} -Dsonar.projectName='${env.SONAR_PROJECT_NAME}'"
        }
    }
}

def buildApplication(appType) {
    switch(appType) {
        case 'springboot':
        case 'tomcat-war':
            sh "mvn -Dmaven.test.skip=true clean install -X"
            break
        default:
            echo "No build step required for ${appType}"
    }
}

def buildAndPushDockerImage(appType) {
    sh '''
        docker login -u ${CIR_USER} -p ${CIR_PW} ${CIR}
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
    
    // Set resource limits
    def resourcePatch = '{"limits": {"memory": "' + env.MEMORY_LIMIT + '"}'
    if (env.CPU_LIMIT) {
        resourcePatch += ', "cpu": "' + env.CPU_LIMIT + '"'
    }
    resourcePatch += '}'
    
    sh """
        kubectl -n \${KUB_NAMESPACE} patch deployment \${APP_NAME} --type='json' -p='[{"op": "add","path": "/spec/template/spec/containers/0/resources","value": ${resourcePatch}}]'
    """
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
        mail to: env.SUCCESS_EMAIL,
             subject: subject,
             body: body
    } else {
        mail to: env.FAILURE_EMAIL,
             cc: env.FAILURE_CC,
             subject: subject,
             body: body
    }
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

return this