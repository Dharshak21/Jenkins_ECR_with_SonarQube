pipeline {
    agent any
    parameters {
        string(name: 'Git_Hub_URL', description: 'Enter the GitHub URL')
        string(name: 'AWS_Account_Id', description: 'Enter the AWS Account Id')
        string(name: 'MailToRecipients', description: 'Enter the Mail Id for Approval')
        string(name: 'Docker_File_Name', description: 'Enter the Name of Your Dockerfile (Eg:DockerFile)')
        choice(choices: ["us-east-1","us-east-2","us-west-1","us-west-2","ap-south-1","ap-northeast-3","ap-northeast-2","ap-southeast-1","ap-southeast-2","ap-northeast-1","ca-central-1","eu-central-1","eu-west-1","eu-west-2","eu-west-3","eu-north-1","sa-east-1"],
               description: 'Select your Region Name',
               name: 'Region_Name')  
        string(name: 'ECR_Repo_Name', defaultValue: 'ecr_default_repo', description: 'ECR Repositary (Default: ecr_default_repo)') 
        string(name: 'Version_Number', defaultValue: '1.0', description: 'Enter the Version Number for ECR Image (Default: 1.0)')
        string(name: 'Workspace_name', defaultValue: 'Jenkins_ECR_with_SonarQube_CodeTest', description: 'Workspace name')      
        string(name: 'AWS_Credentials_Id', defaultValue: 'AWS_Credentials', description: 'AWS Credentials Id')
        string(name: 'Git_Credentials_Id', defaultValue: 'Github_Credentials', description: 'Git Credentials Id')
        string(name: 'SONAR_PROJECT_NAME', defaultValue: 'SonarScannerCheck', description: 'Sonar Project Name (Default: SonarScannerCheck)')
    }
    
    environment {
        ECR_Credentials = "ecr:${Region_Name}:AWS_Credentials"
        S3_Url = 'https://yamlclusterecs1.s3.amazonaws.com/master.yaml'
    }
    
    stages {
        stage('Clone the Git Repository') {
            steps {
                git branch: 'main', credentialsId: "${Git_Credentials_Id}", url: "${Git_Hub_URL}"
            }
        }
        
        stage('Docker start') {
            steps {
                script {
                    // Alternative approach to Docker permissions
                    sh '''
                    if ! sudo chmod 666 /var/run/docker.sock; then
                        echo "Failed to change Docker socket permissions, trying alternative approach"
                        sudo usermod -aG docker jenkins
                        sudo systemctl restart docker
                    fi
                    
                    # Start containers with error handling
                    docker start sonarqube || docker run -d --name sonarqube -p 9000:9000 sonarqube
                    docker start zaproxy || docker run -dt --name zaproxy -p 8082:8080 zaproxy/zap-stable:latest /bin/bash
                    docker exec zaproxy mkdir -p /zap/wrk || true
                    curl -s ipinfo.io/ip > ip.txt
                    '''
                }
            }
        }
        
        stage('Wait for SonarQube to Start') {
            steps {
                script {
                    // More reliable wait using curl
                    def sonarReady = false
                    def attempts = 0
                    while (!sonarReady && attempts < 30) {
                        attempts++
                        try {
                            def response = sh(script: 'curl -s -o /dev/null -w "%{http_code}" http://localhost:9000', returnStdout: true).trim()
                            if (response == "200") {
                                sonarReady = true
                                echo "SonarQube is ready!"
                            } else {
                                sleep 10
                            }
                        } catch (Exception e) {
                            sleep 10
                        }
                    }
                    if (!sonarReady) {
                        error("SonarQube did not start within 5 minutes")
                    }
                }
            }
        }
        
        stage('SonarQube Analysis') {
            steps {
                script {
                    def scannerHome = tool 'sonarqube'
                    withSonarQubeEnv('Default') {
                        sh "${scannerHome}/bin/sonar-scanner -Dsonar.projectKey=${SONAR_PROJECT_NAME}"
                    }
                }
            }
        }
        
        stage('Send Sonar Analysis Report and Approval Email for Build Image') {
            steps {
                script {
                    def Jenkins_IP = sh(returnStdout: true, script: 'cat ip.txt').trim()
                    emailext (
                        subject: "Approval Needed to Build Docker Image",
                        body: """
                        SonarQube Analysis Report URL: http://${Jenkins_IP}:9000/dashboard?id=${SONAR_PROJECT_NAME}
                        Username: admin
                        Password: Sonarqube01@
                        Please Approve to Build the Docker Image in Testing Environment
                        ${BUILD_URL}input/
                        """,
                        mimeType: 'text/html',
                        recipientProviders: [[$class: 'CulpritsRecipientProvider'], [$class: 'RequesterRecipientProvider']],
                        from: "dummymail",
                        to: "${MailToRecipients}",              
                    )
                }
            }
        }
        
        stage('Approval-Build Image') {
            steps {
                timeout(time: 30, unit: 'MINUTES') {
                    input message: 'Please approve the build image process by clicking the link provided in the email.', ok: 'Proceed'
                }
            }
        }
        
        stage('Create a ECR Repository') {
            steps {
                withCredentials([[
                    $class: 'AmazonWebServicesCredentialsBinding',
                    credentialsId: "${AWS_Credentials_Id}",
                    accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                    secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) 
                {
                    sh '''
                    aws ecr create-repository --repository-name ${ECR_Repo_Name} --region ${Region_Name} || true
                    cd /var/lib/jenkins/workspace/${Workspace_name}
                    '''
                }
            }
        }
        
        stage('Build and Push the Docker Image to ECR Repository') {
            steps {
                withDockerRegistry(credentialsId: "${ECR_Credentials}", url: "https://${AWS_Account_Id}.dkr.ecr.${Region_Name}.amazonaws.com") 
                {
                    script {
                        def DockerfilePath = sh(script: 'find -name ${Docker_File_Name} | head -1', returnStdout: true).trim()
                        DockerfilePath = DockerfilePath.replaceAll('^\\.[\\\\/]', '')
                        echo("Dockerfile path: ${DockerfilePath}")
                        
                        // Get ECR login
                        sh """
                        aws ecr get-login-password --region ${Region_Name} | docker login --username AWS --password-stdin ${AWS_Account_Id}.dkr.ecr.${Region_Name}.amazonaws.com
                        docker build -t ${ECR_Repo_Name} -f ${DockerfilePath} .
                        docker tag ${ECR_Repo_Name}:latest ${AWS_Account_Id}.dkr.ecr.${Region_Name}.amazonaws.com/${ECR_Repo_Name}:${Version_Number}
                        docker push ${AWS_Account_Id}.dkr.ecr.${Region_Name}.amazonaws.com/${ECR_Repo_Name}:${Version_Number}
                        """
                    }
                }
            }
        }
    }
}
