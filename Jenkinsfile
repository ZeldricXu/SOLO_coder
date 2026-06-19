pipeline {
    agent any

    options {
        buildDiscarder(logRotator(numToKeepStr: '30'))
        timeout(time: 120, unit: 'MINUTES')
        timestamps()
        disableConcurrentBuilds()
    }

    parameters {
        string(name: 'BRANCH', defaultValue: 'main', description: 'Git分支')
        string(name: 'MAVEN_OPTS', defaultValue: '-Xmx4g -XX:MaxMetaspaceSize=1g', description: 'Maven参数')
        booleanParam(name: 'RUN_SINGULARITY', defaultValue: true, description: '是否构建Singularity镜像')
        booleanParam(name: 'RUN_INTEGRATION', defaultValue: true, description: '是否运行集成测试')
        string(name: 'JDK_VERSION', defaultValue: '21', description: 'JDK版本')
    }

    environment {
        MAVEN_HOME = tool name: 'maven-3.9.6', type: 'maven'
        JAVA_HOME = tool name: 'jdk-21.0.2', type: 'jdk'
        PATH = "$JAVA_HOME/bin:$MAVEN_HOME/bin:$PATH"
        PROJECT_DIR = 'DF1-85'
        VERSION = sh(script: 'date +%Y%m%d%H%M%S', returnStdout: true).trim()
        BUILD_TAG = "nwp-solver-${VERSION}-${BUILD_NUMBER}"
        MAVEN_OPTS = "${params.MAVEN_OPTS}"
    }

    stages {
        stage('Checkout') {
            steps {
                dir(env.PROJECT_DIR) {
                    checkout scm
                    sh 'git log --oneline -5'
                }
            }
            post {
                success {
                    echo "代码检出完成，分支: ${params.BRANCH}"
                }
            }
        }

        stage('Prepare Environment') {
            steps {
                dir(env.PROJECT_DIR) {
                    sh '''
                        echo "=============================="
                        echo "环境信息:"
                        echo "Java版本: $(java -version 2>&1 | head -1)"
                        echo "Maven版本: $(mvn -version 2>&1 | head -1)"
                        echo "工作目录: $(pwd)"
                        echo "=============================="
                    '''
                    sh 'mkdir -p test-reports/unit test-reports/integration coverage logs'
                }
            }
        }

        stage('Compile') {
            steps {
                dir(env.PROJECT_DIR) {
                    echo "开始编译..."
                    sh '''
                        mvn clean compile \
                            -DskipTests \
                            -Dmaven.compiler.source=21 \
                            -Dmaven.compiler.target=21 \
                            -B 2>&1 | tee logs/compile.log
                    '''
                }
            }
            post {
                success {
                    echo "编译成功!"
                }
                failure {
                    archiveArtifacts artifacts: 'logs/compile.log', allowEmptyArchive: true
                    echo "编译失败，查看日志: logs/compile.log"
                }
            }
        }

        stage('Unit Tests') {
            steps {
                dir(env.PROJECT_DIR) {
                    echo "开始单元测试..."
                    sh '''
                        mvn test -P unit-tests \
                            -Dtest="*Test,*Tests,!Integration*" \
                            -DfailIfNoTests=false \
                            -Dmaven.compiler.source=21 \
                            -Dmaven.compiler.target=21 \
                            -B 2>&1 | tee logs/unit-test.log
                    '''
                }
            }
            post {
                always {
                    dir(env.PROJECT_DIR) {
                        junit allowEmptyResults: true, testResults: 'target/surefire-reports/*.xml'
                        archiveArtifacts artifacts: 'test-reports/unit/**, logs/unit-test.log', allowEmptyArchive: true
                    }
                }
                success {
                    echo "单元测试全部通过!"
                }
                failure {
                    echo "单元测试失败，查看测试报告"
                }
            }
        }

        stage('Integration Tests') {
            when {
                expression { params.RUN_INTEGRATION == true }
            }
            steps {
                dir(env.PROJECT_DIR) {
                    echo "开始集成测试（启动HDFS/Kafka单点）..."
                    sh '''
                        chmod +x scripts/ci/setup-integration-env.sh
                        bash scripts/ci/setup-integration-env.sh start
                    '''
                    sh '''
                        mvn test -P integration-tests \
                            -Dtest="Integration*Test,Storage*Test,Parallel*Test" \
                            -DfailIfNoTests=false \
                            -Dnwp.storage.hdfs.namenode="hdfs://localhost:9000" \
                            -Dnwp.storage.kafka.bootstrap-servers="localhost:9092" \
                            -Dmaven.compiler.source=21 \
                            -Dmaven.compiler.target=21 \
                            -B 2>&1 | tee logs/integration-test.log
                    '''
                }
            }
            post {
                always {
                    dir(env.PROJECT_DIR) {
                        sh 'bash scripts/ci/setup-integration-env.sh stop || true'
                        junit allowEmptyResults: true, testResults: 'target/surefire-reports/*.xml'
                        archiveArtifacts artifacts: 'test-reports/integration/**, logs/integration-test.log', allowEmptyArchive: true
                    }
                }
                success {
                    echo "集成测试全部通过!"
                }
                failure {
                    echo "集成测试失败"
                }
            }
        }

        stage('Coverage') {
            steps {
                dir(env.PROJECT_DIR) {
                    echo "生成覆盖率报告..."
                    sh '''
                        mvn test -P coverage \
                            -DfailIfNoTests=false \
                            -Dtest="*Test,*Tests" \
                            -Dmaven.compiler.source=21 \
                            -Dmaven.compiler.target=21 \
                            -B 2>&1 | tee logs/coverage.log
                    '''
                }
            }
            post {
                always {
                    dir(env.PROJECT_DIR) {
                        publishCoverage adapters: [
                            jacocoAdapter('target/site/jacoco/jacoco.xml')
                        ], sourceFileResolver: sourceFiles('STORE_ALL_BUILD')
                        archiveArtifacts artifacts: 'target/site/jacoco/**, logs/coverage.log', allowEmptyArchive: true
                    }
                }
            }
        }

        stage('Package') {
            steps {
                parallel {
                    stage('Build Fat JAR') {
                        steps {
                            dir(env.PROJECT_DIR) {
                                echo "构建可执行JAR包..."
                                sh '''
                                    mvn package -DskipTests -B \
                                        -Dmaven.compiler.source=21 \
                                        -Dmaven.compiler.target=21 \
                                        -DfinalName=nwp-core-solver-${BUILD_TAG} \
                                        2>&1 | tee logs/package.log
                                '''
                                sh '''
                                    mvn assembly:single -DskipTests -B \
                                        -DdescriptorId=jar-with-dependencies \
                                        -DfinalName=nwp-core-solver-${BUILD_TAG}-with-deps \
                                        2>&1 | tee -a logs/package.log
                                '''
                            }
                        }
                    }
                    stage('Build Singularity Image') {
                        when {
                            expression { params.RUN_SINGULARITY == true }
                        }
                        steps {
                            dir(env.PROJECT_DIR) {
                                echo "构建Singularity镜像..."
                                sh '''
                                    if command -v singularity &> /dev/null || command -v apptainer &> /dev/null; then
                                        SIF_CMD=$(command -v singularity || command -v apptainer)
                                        mkdir -p singularity-images
                                        cp target/nwp-core-solver-*.jar target/nwp-core-solver-latest.jar || true
                                        $SIF_CMD build --fakeroot \
                                            singularity-images/nwp-solver-${BUILD_TAG}.sif \
                                            Singularity.def 2>&1 | tee logs/singularity-build.log
                                        ln -sf nwp-solver-${BUILD_TAG}.sif singularity-images/nwp-solver-latest.sif
                                    else
                                        echo "Warning: Singularity/Apptainer not found, skipping image build"
                                        mkdir -p singularity-images
                                        echo "# Singularity build skipped - no runtime available" > singularity-images/README.txt
                                    fi
                                '''
                            }
                        }
                    }
                }
            }
            post {
                success {
                    dir(env.PROJECT_DIR) {
                        archiveArtifacts artifacts: '''
                            target/nwp-core-solver-*.jar,
                            target/nwp-core-solver-*-with-deps.jar,
                            singularity-images/*.sif,
                            singularity-images/*.txt,
                            scripts/**,
                            config/**,
                            logs/*.log
                        ''', allowEmptyArchive: true
                    }
                    echo "打包完成: ${BUILD_TAG}"
                }
                failure {
                    dir(env.PROJECT_DIR) {
                        archiveArtifacts artifacts: 'logs/package.log, logs/singularity-build.log', allowEmptyArchive: true
                    }
                }
            }
        }

        stage('Deploy to Staging') {
            when {
                branch 'develop'
            }
            steps {
                dir(env.PROJECT_DIR) {
                    echo "部署到预发布环境 (可选)"
                    sh '''
                        echo "Staging deployment steps would go here"
                        echo "  - Copy JAR/SIF to staging HPC cluster"
                        echo "  - Submit smoke test jobs to SLURM"
                    '''
                }
            }
        }
    }

    post {
        always {
            dir(env.PROJECT_DIR) {
                echo "========================================"
                echo "流水线完成: ${currentBuild.currentResult}"
                echo "构建号: ${BUILD_NUMBER}"
                echo "标签: ${BUILD_TAG}"
                echo "========================================"
            }
            cleanWs notFailBuild: true, deleteDirs: false, patterns: [
                [pattern: '**/*.class', type: 'INCLUDE'],
                [pattern: '**/target/surefire-reports/**', type: 'EXCLUDE']
            ]
        }
        success {
            echo "✅ 所有阶段完成，构建成功!"
        }
        failure {
            echo "❌ 构建失败，请查看日志"
        }
        aborted {
            echo "⚠️  构建被中止"
        }
    }
}
