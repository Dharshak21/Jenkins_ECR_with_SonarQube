pipeline {
    agent any
    parameters {
        string(name: 'Git_Hub_URL', description: 'Enter the GitHub URL')
        string(name: 'AWS_Account_Id' ,description: 'Enter the AWS Account Id')
        string(name: 'MailToRecipients' ,description: 'Enter the Mail Id for Approval')
        string(name: 'Endpoint_URL' ,description: 'Enter the Endpoint URL for OWASP Analysis')
        string(name: 'Docker_File_Name' ,description: 'Enter the Name of Your Dockerfile (Eg:DockerFile)')
        string(name: 'Container_Port', defaultValue: '80',description: 'Enter the port number for the Container to expose (Default: 80)')
        choice  (choices: ["us-east-1","us-east-2","us-west-1","us-west-2","ap-south-1","ap-northeast-3","ap-northeast-2","ap-southeast-1","ap-southeast-2","ap-northeast-1","ca-central-1","eu-central-1","eu-west-1","eu-west-2","eu-west-3","eu-north-1","sa-east-1"],
                 description: 'Select your Region Name (eg: us-east-1). To Know your region code refer URL "https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/Concepts.RegionsAndAvailabilityZones.html#Concepts.RegionsAndAvailabilityZones.Regions" ',
                 name: 'Region_Name')  
        string(name: 'ECR_Repo_Name', defaultValue: 'ecr_default_repo',description: 'ECR Repositary (Default: ecr_default_repo)') 
        string(name: 'Version_Number', defaultValue: '1.0', description: 'Enter the Version Number for ECR Image (Default: 1.0)')
        string(name: 'Workspace_name',defaultValue: 'Jenkins_ECS_Fargate_Pipeline_For_CodeTesting_with_OWASP+SonarQube',description: 'Workspace name')      
        string(name: 'AWS_Credentials_Id',defaultValue: 'AWS_Credentials', description: 'AWS Credentials Id')
        string(name: 'Git_Credentials_Id',defaultValue: 'Github_Credentials',description: 'Git Credentials Id')
        string(name: 'Stack_Name', defaultValue: 'ECS' ,description: 'Stack Name (Default: ECS)')
        string(name: 'SONAR_PROJECT_NAME',defaultValue: 'SonarScannerCheck' ,description: 'Sonar Project Name (Default: SonarScannerCheck)')
        choice  (choices: ["Baseline", "Full"],
                 description: 'Type of scan for OWASP Analysis',
                 name: 'SCAN_TYPE')
    }
    environment {
        ECR_Credentials = "ecr:${Region_Name}:AWS_Credentials"
        S3_Url          = 'https://yamlclusterecs1.s3.amazonaws.com/master.yaml'
    }
    stages {
        stage('Clone the Git Repository') {
            steps {
                git branch: 'main', credentialsId: "${Git_Credentials_Id}", url: "${Git_Hub_URL}"
                }
        }
        stage('Docker start') {
            steps {
                sh '''
                docker ps
                docker start sonarqube
                docker start zaproxy
                curl ipinfo.io/ip > ip.txt
                '''
            }
        }
     stage('Wait for SonarQube to Start') {
          steps {
               script {
                   sleep 120 
               }
            }
        }
        
       stage('Send Sonar Analysis Report and Approval Email') {
            steps {
                script {
                    def Jenkins_IP = sh(script: 'cat ip.txt', returnStdout: true).trim()
                    emailext(
                        subject: "Approval Needed to Build Docker Image",
                        body: """
                            <p>SonarQube Analysis Report URL: http://${Jenkins_IP}:9000/dashboard?id=${params.SONAR_PROJECT_NAME}</p>
                            <p>Username: admin<br>Password: 12345</p>
                            <p>Please Approve to Build the Docker Image in Testing Environment</p>
                            <p><a href="${env.BUILD_URL}input/">Click to Approve</a></p>
                        """,
                        mimeType: 'text/html',
                        to: "${params.MailToRecipients}",
                        from: "dharshak214@gmail.com"   
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
       stage('Build and Push Docker Image') {
            steps {
                withCredentials([
                    [$class: 'AmazonWebServicesCredentialsBinding', 
                     credentialsId: "${params.AWS_Credentials_Id}", 
                     accessKeyVariable: 'AWS_ACCESS_KEY_ID', 
                     secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']
                ]) {
                    script {
                        // Find Dockerfile path
                        def dockerfilePath = sh(script: "find . -type f -name '${params.Docker_File_Name}' | head -n 1", returnStdout: true).trim()
                        
                        if (!dockerfilePath) {
                            error "Dockerfile not found in workspace!"
                        }
                        
                        echo "Found Dockerfile at: ${dockerfilePath}"
                        
                        def imageName = "${params.AWS_Account_Id}.dkr.ecr.${params.Region_Name}.amazonaws.com/${params.ECR_Repo_Name}:${params.Version_Number}"
                        
                        sh """
                            echo "Building Docker image: ${imageName}"
                            aws ecr get-login-password --region ${params.Region_Name} | docker login --username AWS --password-stdin ${params.AWS_Account_Id}.dkr.ecr.${params.Region_Name}.amazonaws.com
                            docker build -t ${imageName} -f ${dockerfilePath} .
                            docker push ${imageName}
                        """
             
            }
        }
            }
        }
        
        stage('Deploy the Stack') {
            steps {
                withCredentials([[
                $class: 'AmazonWebServicesCredentialsBinding',
                credentialsId: "${AWS_Credentials_Id}",
                accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) 
                {
                script {
                    def stackExists = sh(
                        returnStatus: true,
                        script: 'aws cloudformation describe-stacks --stack-name ${Stack_Name}'
                    )
                    if (stackExists == 0) {
                        script {
                            sh '''
                            aws cloudformation update-stack --stack-name ${Stack_Name} --template-url ${S3_Url} --capabilities CAPABILITY_NAMED_IAM  --parameters ParameterKey=ImageId,ParameterValue=${AWS_Account_Id}.dkr.ecr.${Region_Name}.amazonaws.com/${ECR_Repo_Name}:${Version_Number} ParameterKey=ContainerPort,ParameterValue=${Container_Port} ParameterKey=InstanceName,UsePreviousValue=true ParameterKey=KeyName,UsePreviousValue=true ParameterKey=InstanceType,UsePreviousValue=true ParameterKey=VpcCIDR,UsePreviousValue=true ParameterKey=VolumeSize,UsePreviousValue=true ParameterKey=ClusterName,UsePreviousValue=true ParameterKey=PublicSubnet1CIDR,UsePreviousValue=true ParameterKey=AvailabilityZone1,UsePreviousValue=true ParameterKey=PublicSubnet2CIDR,UsePreviousValue=true ParameterKey=AvailabilityZone2,UsePreviousValue=true ParameterKey=PrivateSubnet1CIDR,UsePreviousValue=true ParameterKey=AvailabilityZone3,UsePreviousValue=true ParameterKey=PrivateSubnet2CIDR,UsePreviousValue=true ParameterKey=AvailabilityZone4,UsePreviousValue=true ParameterKey=PerformanceMode,UsePreviousValue=true ParameterKey=EfsProvisionedThroughputInMibps,UsePreviousValue=true ParameterKey=ThroughputMode,UsePreviousValue=true || true 
                           '''
                        }
                    } else {
                        script {
                            sh '''
                            aws cloudformation create-stack --stack-name ${Stack_Name} --template-url ${S3_Url} --capabilities CAPABILITY_NAMED_IAM  --parameters ParameterKey=ImageId,ParameterValue=${AWS_Account_Id}.dkr.ecr.${Region_Name}.amazonaws.com/${ECR_Repo_Name}:${Version_Number} ParameterKey=ContainerPort,ParameterValue=${Container_Port}
                            '''
                        }
                    }

                }
            }
        } }
        stage('Wait for Stack Update') {
            steps {
                withCredentials([[
                $class: 'AmazonWebServicesCredentialsBinding',
                credentialsId: "${AWS_Credentials_Id}",
                accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) 
                {
                script {
                   def stackStatus = sh(returnStdout: true, script: "aws cloudformation describe-stacks --stack-name ${Stack_Name} --query 'Stacks[0].StackStatus' --output text").trim()
                    while (stackStatus.contains('UPDATE_IN_PROGRESS')) {
                        echo "Waiting for the stack ${Stack_Name}  update to complete..."
                        sleep 30 
                        stackStatus = sh(returnStdout: true, script: "aws cloudformation describe-stacks --stack-name ${Stack_Name} --query 'Stacks[0].StackStatus' --output text").trim()
                    }
                }
            }
        }
        }
         stage('Scanning target on owasp container') {
             steps {
                 script {
                     scan_type = "${params.SCAN_TYPE}"
                     echo "----> scan_type: $scan_type"
                     target = "${Endpoint_URL}"
                     if(scan_type == "Baseline"){
                         sh """
                             docker exec zaproxy /zap/zap-baseline.py -t $target -r report.html -I 
                         """
                     }
                    else if(scan_type == "Full"){
                         sh """
                             docker exec zaproxy /zap/zap-full-scan.py -t $target -r report.html -I
                         """
                          }
                     else{
                         echo "Something went wrong..."
                     }
                 }
             }
         }
         stage('Copy Report to Workspace'){
             steps {
                 script {
                     sh '''
                            docker cp zaproxy:/zap/wrk/report.html ${WORKSPACE}/report.html
                     '''
                 }
             }
         }
         stage('Final OWASP Report') {
            steps {
                emailext (
                    subject: "OWASP Report",
                    body: '${FILE,path="report.html"} \n Application Successfully Deployed in AWS ECS and Verify the OWASP Report\n\n${BUILD_URL}input/',
                    mimeType: 'text/html',
                    recipientProviders: [[$class: 'CulpritsRecipientProvider'], [$class: 'RequesterRecipientProvider']],
                    from: "dharshak214",
                    to: "${MailToRecipients}",
                    attachLog: true
                )
            }
        }

}
}
