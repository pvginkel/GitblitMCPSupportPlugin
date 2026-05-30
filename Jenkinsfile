import org.jenkinsci.plugins.pipeline.modeldefinition.Utils

library('JenkinsPipelineUtils') _

podTemplate(inheritFrom: 'jenkins-agent kaniko') {
    node(POD_LABEL) {
        stage('Cloning repo') {
            git branch: 'main',
                credentialsId: '5f6fbd66-b41c-405f-b107-85ba6fd97f10',
                url: 'https://github.com/pvginkel/GitblitSearchApiPlugin.git'
        }

        stage("Building GitblitSearchApiPlugin") {
            container('kaniko') {
                helmCharts.kaniko([
                    "registry:5000/gitblit-initializer:${currentBuild.number}",
                    "registry:5000/gitblit-initializer:latest"
                ])
            }
        }

        stage('Deploy Helm charts') {
            cicd.helmDeploy()
        }
    }
}
