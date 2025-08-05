# Mobitel Pipeline Shared Library

## Repository Structure

Create a new Git repository for your shared library with the following structure:

```
mobitel-pipeline-lib/
├── vars/
│   ├── mobitelPipeline.groovy          # Main pipeline function
│   └── pipelineHelpers.groovy          # Helper functions
├── src/
│   └── org/
│       └── mobitel/
│           └── pipeline/
│               └── Utils.groovy         # Optional utility classes
├── resources/
│   ├── dockerfiles/
│   │   ├── Dockerfile.springboot       # Template Dockerfiles
│   │   ├── Dockerfile.angular
│   │   └── Dockerfile.react
│   └── k8s/
│       └── deployment-template.yaml    # Kubernetes templates
└── README.md
```

## Setup Instructions

### 1. Configure Jenkins Global Pipeline Libraries

1. Go to **Manage Jenkins** → **Configure System**
2. Scroll to **Global Pipeline Libraries**
3. Click **Add** and configure:
   - **Name**: `mobitel-pipeline-lib`
   - **Default version**: `main` (or your preferred branch)
   - **Retrieval method**: Modern SCM
   - **Source Code Management**: Git
   - **Project Repository**: `https://your-git-server/mobitel-pipeline-lib.git`
   - **Credentials**: Select appropriate Git credentials
   - Check **Load implicitly** to make it available to all pipelines

### 2. Required Jenkins Plugins

Ensure these plugins are installed:
- Pipeline: Groovy
- Pipeline: Stage Step
- Docker Pipeline
- Kubernetes CLI
- SonarQube Scanner (if using SonarQube)
- Email Extension Plugin

### 3. Jenkins Credentials

Configure these credentials in Jenkins:
- `cir-pw`: Docker registry password
- Git credentials for accessing repositories
- SonarQube token (if using SonarQube)

## Supported Application Types

| App Type | Description | Build Tool | Default Port |
|----------|-------------|------------|--------------|
| `angular-nginx` | Angular app served by Nginx | Docker only | 80 |
| `react-nginx` | React app served by Nginx | Docker only | 80 |
| `react-normal` | React development server | Docker only | 3000 |
| `springboot` | Spring Boot application | Maven | 8080 |
| `tomcat-war` | WAR file deployed to Tomcat | Maven | 8080 |

## Configuration Parameters

### Required Parameters

- `appType`: Application type (see supported types above)
- `appName`: Application name (used for deployment and service names)

### Optional Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `environment` | `'stg'` | Environment (stg, pr, dev) |
| `namespace` | `'intsys'` | Kubernetes namespace |
| `exposePort` | Auto-detected | Service port to expose |
| `harbourSecret` | `'harbor-intsys'` | Image pull secret name |
| `project` | `'mobitel_pipeline'` | Docker registry project |
| `memoryLimit` | `'512Mi'` | Pod memory limit |
| `cpuLimit` | `null` | Pod CPU limit |
| `sonarEnabled` | `false` | Enable SonarQube analysis |
| `sonarProjectKey` | `appName` | SonarQube project key |
| `sonarProjectName` | `appName` | SonarQube project name |
| `successEmail` | `'mobiteldev@mobitel.lk'` | Success notification email |
| `failureEmail` | `'jenkins.notification@mobitel.lk'` | Failure notification email |
| `failureCC` | `'mobiteldev@mobitel.lk'` | CC for failure notifications |
| `mavenImage` | `'maven:3.9.6-amazoncorretto-21'` | Maven Docker image |
| `cicdToolsImage` | `'inovadockerimages/cicdtools:latest'` | kubectl tools image |
| `trivyImage` | `'aquasec/trivy:latest'` | Security scanner image |

## Features

### 🚀 **Automated Build & Deploy**
- Supports multiple application types
- Automatic port detection
- Docker image building and pushing
- Kubernetes deployment with rollback support

### 🔒 **Security**
- Container vulnerability scanning with Trivy
- Image pull secrets management
- Resource limits enforcement

### 📊 **Quality Assurance**
- Optional SonarQube integration
- Build and test automation for Java applications

### 📧 **Notifications**
- Email notifications for success/failure
- Customizable recipients

### 🏷️ **Metadata Management**
- Git information extraction
- Deployment annotations with build info

## Migration from Existing Jenkinsfiles

To migrate your existing Jenkinsfiles:

1. **Identify your application type** based on the technology stack
2. **Extract configuration values** (app name, namespace, ports, etc.)
3. **Replace your Jenkinsfile** with the simplified version using the shared library
4. **Test the migration** in a development environment first

### Before (Original Jenkinsfile: ~150 lines)
```groovy
pipeline {
  environment {
     ENV="stg"
     PROJECT = "mobitel_pipeline"
     APP_NAME = "api-dev-portal-fr"
     // ... 50+ more lines of configuration
  }
  agent none 
  stages {  
    // ... 100+ lines of pipeline logic
  }
  post {
    // ... email notification logic
  }
}
```

### After (Using Shared Library: ~8 lines)
```groovy
@Library('mobitel-pipeline-lib') _

mobitelPipeline {
    appType = 'angular-nginx'
    appName = 'api-dev-portal-fr'
    environment = 'stg'
    namespace = 'intsys'
}
```

## Benefits

- **📉 90% reduction in Jenkinsfile size**
- **🔄 Standardized CI/CD processes**
- **🛠️ Centralized maintenance**
- **🐛 Easier debugging and troubleshooting**
- **📈 Improved consistency across projects**
- **⚡ Faster pipeline setup for new projects**

## Troubleshooting

### Common Issues

1. **Library not found**: Ensure the library is configured in Jenkins Global Pipeline Libraries
2. **Docker login fails**: Check the `cir-pw` credential is correctly set
3. **Kubectl command fails**: Verify the certificate path `/root/.cert/${ENV}/config` exists
4. **Image pull fails**: Confirm the Harbor secret is correctly configured in the namespace

### Debugging

Enable debug mode by adding this to your Jenkinsfile:
```groovy
mobitelPipeline {
    // your config
    debug = true
}
```

## Contributing

To extend the shared library:

1. Create feature branches for new functionality
2. Test changes in a development environment
3. Update documentation for new parameters or features
4. Submit pull requests for review